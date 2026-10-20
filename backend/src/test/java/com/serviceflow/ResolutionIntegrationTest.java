package com.serviceflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResolutionIntegrationTest extends MySqlTestSupport {
    static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(START);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return time.get(); }
        void advance(long millis) { time.updateAndGet(t -> t.plusMillis(millis)); }
    }
    @TestConfiguration static class Environment {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
        @Bean S2IntegrationTest.TaskLockProbe taskLockProbe() { return new S2IntegrationTest.TaskLockProbe(); }
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired S2IntegrationTest.TaskLockProbe probe;
    String customer, support, manager, engineer, backup;
    record Ref(long ticket, long task, int ticketVersion, int taskVersion) {}
    record Proposal(Ref ref, long id, int version) {}

    @BeforeEach void reset() {
        clean(); clock.time.set(START);
        customer=login("customer_a"); support=login("support"); manager=login("manager");
        engineer=login("engineer"); backup=login("engineer_backup");
    }
    @AfterEach void clean() {
        probe.clear();
        jdbc.execute("DROP TRIGGER IF EXISTS resolution_failure");
        for(String table:List.of("ticket_triage_record","ai_triage_proposal","ai_triage_run","ticket_activity",
                "ticket_acceptance","task_assignment_history","service_task","ticket_resolution",
                "ticket_sla_event","ticket_sla_instance","business_audit_log","service_ticket","idempotency_request"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE app_user SET account_status='ACTIVE'");
        jdbc.update("DELETE FROM customer_user WHERE customer_id=1 AND user_id=2");
        jdbc.update("INSERT IGNORE INTO customer_user(customer_id,user_id) VALUES(1,1)");
        jdbc.update("UPDATE sla_policy_binding SET active_policy_version_id=1 WHERE service_type='DATABASE_SUPPORT' AND priority='P1'");
    }

    @Test @DisplayName("方案、拒绝整改、重新派工、二轮验收完整闭环，累计 4.5 小时")
    void rejectionAndClosureLifecycle() {
        Ref first=completed();
        clock.advance(3*3_600_000L);
        Proposal p=propose(first);
        assertThat(sla(first.ticket()).get("resolution_clock_state")).isEqualTo("PAUSED_ACCEPTANCE");
        assertThat(sla(first.ticket()).get("first_responded_at")).isNull();
        clock.advance(2*3_600_000L);
        var rejected=ok(decide(p,"REJECT","仍然超时",customer,"reject"),200);
        long rework=rejected.path("remediationTaskId").asLong();
        assertThat(sla(first.ticket()).get("resolution_elapsed_millis")).isEqualTo(10_800_000L);
        assertThat(sla(first.ticket()).get("resolution_due_at")).isEqualTo(LocalDateTime.parse("2026-10-20T14:00:00"));
        var task=jdbc.queryForMap("SELECT * FROM service_task WHERE id=?",rework);
        assertThat(task.get("status")).isEqualTo("CREATED");
        assertThat(task.get("current_assignee_id")).isNull();
        assertThat(((Number)task.get("source_resolution_id")).longValue()).isEqualTo(p.id());
        assertThat(task.get("resolution_cycle_no")).isEqualTo(2);
        assertThat(get("/tickets/"+first.ticket(),engineer).getStatusCode().value()).isEqualTo(404);
        Ref next=new Ref(first.ticket(),rework,rejected.path("ticketVersion").asInt(),0);
        next=work(next,8,backup);
        clock.advance(90*60_000L);
        Proposal second=propose(next,backup);
        assertThat(second.version()).isEqualTo(13);
        ok(decide(second,"ACCEPT",null,customer,"accept"),200);
        var state=sla(first.ticket());
        assertThat(state.get("resolution_clock_state")).isEqualTo("CLOSED");
        assertThat(state.get("resolution_elapsed_millis")).isEqualTo(16_200_000L);
        assertThat(((Number)state.get("resolution_met")).intValue()).isZero();
        assertThat(((Number)state.get("response_met")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT closed_at FROM service_ticket WHERE id=?",java.sql.Timestamp.class,first.ticket()).toInstant()).isEqualTo(clock.instant());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_task WHERE status='COMPLETED'",Integer.class)).isEqualTo(2);
        var history=ok(get("/tickets/"+first.ticket()+"/resolutions",customer),200);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("decision").asText()).isEqualTo("REJECT");
        assertThat(history.get(1).path("decision").asText()).isEqualTo("ACCEPT");
        assertThat(history.get(0).path("remediationTaskId").asLong()).isEqualTo(rework);
        assertThat(jdbc.queryForList("SELECT event_type FROM ticket_sla_event ORDER BY sequence_no",String.class))
                .containsExactly("START","RESOLUTION_PROPOSED","ACCEPTANCE_REJECTED","RESOLUTION_PROPOSED","CLOSED");
    }

    @ParameterizedTest @CsvSource({"14399999,1","14400000,1","14400001,0"})
    @DisplayName("解决目标毫秒边界和冻结的四小时策略")
    void exactResolutionBoundary(long elapsed, int met) {
        Ref r=completed();
        jdbc.update("UPDATE sla_policy_binding SET active_policy_version_id=7 WHERE service_type='DATABASE_SUPPORT' AND priority='P1'");
        clock.advance(elapsed);
        Proposal p=propose(r);
        clock.advance(86_400_000);
        ok(decide(p,"ACCEPT",null,customer,key()),200);
        assertThat(sla(r.ticket()).get("resolution_elapsed_millis")).isEqualTo(elapsed);
        assertThat(((Number)sla(r.ticket()).get("resolution_met")).intValue()).isEqualTo(met);
        assertThat(((Number)sla(r.ticket()).get("policy_version_id")).longValue()).isEqualTo(1);
    }

    @Test @DisplayName("多轮亚秒区间累计后再取整，不丢失毫秒")
    void subsecondIntervalsAccumulate() {
        Ref r=completed(); clock.advance(600);
        Proposal p=propose(r); clock.advance(60_000);
        var rejected=ok(decide(p,"REJECT","继续排查",customer,key()),200);
        Ref next=work(new Ref(r.ticket(),rejected.path("remediationTaskId").asLong(),rejected.path("ticketVersion").asInt(),0),5,engineer);
        clock.advance(600);
        Proposal second=propose(next);
        ok(decide(second,"ACCEPT",null,customer,key()),200);
        assertThat(sla(r.ticket()).get("resolution_elapsed_millis")).isEqualTo(1200L);
        assertThat(sla(r.ticket()).get("resolution_elapsed_seconds")).isEqualTo(1L);
    }

    @Test @DisplayName("超期暂停恢复保留超期事实，剩余时间钳制为零")
    void overdueResumeDoesNotResetElapsed() {
        Ref r=completed(); clock.advance(14_400_001);
        Proposal p=propose(r); clock.advance(60_000);
        var response=ok(decide(p,"REJECT","仍需整改",customer,key()),200);
        assertThat(((LocalDateTime)sla(r.ticket()).get("resolution_due_at")).toInstant(ZoneOffset.UTC)).isEqualTo(clock.instant());
        assertThat(sla(r.ticket()).get("resolution_elapsed_millis")).isEqualTo(14_400_001L);
        Proposal second=propose(work(new Ref(r.ticket(),response.path("remediationTaskId").asLong(),response.path("ticketVersion").asInt(),0),5,engineer));
        ok(decide(second,"ACCEPT",null,customer,key()),200);
        assertThat(((Number)sla(r.ticket()).get("resolution_met")).intValue()).isZero();
    }

    @ParameterizedTest @CsvSource({"3600000,1","3600001,0"})
    @DisplayName("待验收仍可人工首响；方案本身不停止首响")
    void firstResponseWhilePaused(long elapsed, int met) {
        Proposal p=propose(completed());
        assertThat(sla(p.ref().ticket()).get("response_met")).isNull();
        clock.advance(elapsed);
        var message=ok(post("/tickets/"+p.ref().ticket()+"/messages",
                Map.of("expectedTicketVersion",p.version(),"content","工程师公开说明"),engineer,key()),201);
        var command=new LinkedHashMap<String,Object>();
        command.put("expectedTicketVersion",p.version()+1); command.put("decision","ACCEPT");
        ok(post(acceptance(p),command,customer,key()),200);
        assertThat(((Number)sla(p.ref().ticket()).get("response_met")).intValue()).isEqualTo(met);
        assertThat(sla(p.ref().ticket()).get("resolution_elapsed_millis")).isEqualTo(0L);
        assertThat(message.path("ticketId").asLong()).isEqualTo(p.ref().ticket());
    }

    @Test @DisplayName("提交方案必须是本轮完成任务的当前负责人，其他角色不可代理")
    void submissionAuthorizationAndTaskState() {
        Ref r=created();
        r=assign(r,5);
        code(post(resolutions(r),submit(r),engineer,key()),422,"INVALID_STATE_TRANSITION");
        Ref done=finish(r,engineer);
        code(post(resolutions(done),submit(done),backup,key()),404,"RESOURCE_FORBIDDEN");
        code(post(resolutions(done),submit(done),manager,key()),403,"RESOURCE_FORBIDDEN");
        var wrongTask=submit(done); wrongTask.put("taskId",999999);
        code(post(resolutions(done),wrongTask,engineer,key()),404,"RESOURCE_FORBIDDEN");
        assertThat(count("ticket_resolution")).isZero();
        assertThat(get("/tickets/"+done.ticket(),engineer).getStatusCode().value()).isEqualTo(200);
    }

    @Test @DisplayName("转派后的原负责人不能提交方案或读取对客方案")
    void formerOwnerCannotSubmit() {
        Ref r=assign(created(),5);
        var transfer=versions(r); transfer.put("expectedAssigneeId",5); transfer.put("assigneeId",8); transfer.put("reason","更换值班");
        r=ref(ok(post(task(r,"reassignments"),transfer,manager,key()),200));
        Ref done=finish(r,backup);
        code(post(resolutions(done),submit(done),engineer,key()),404,"RESOURCE_FORBIDDEN");
        code(get(resolutions(done),engineer),404,"RESOURCE_FORBIDDEN");
        propose(done,backup);
    }

    @Test @DisplayName("只有工单创建者可以验收，同客户其他联系人及内部角色拒绝")
    void creatorOnlyAcceptanceAndCustomerReadIsolation() {
        Proposal p=propose(completed());
        String other=login("customer_b");
        code(decide(p,"ACCEPT",null,other,key()),404,"RESOURCE_FORBIDDEN");
        code(get(resolutions(p.ref()),other),404,"RESOURCE_FORBIDDEN");
        jdbc.update("INSERT INTO customer_user(customer_id,user_id) VALUES(1,2)");
        ok(get(resolutions(p.ref()),other),200);
        code(decide(p,"ACCEPT",null,other,key()),404,"RESOURCE_FORBIDDEN");
        code(decide(p,"ACCEPT",null,support,key()),403,"RESOURCE_FORBIDDEN");
        assertThat(count("ticket_acceptance")).isZero();
    }

    @Test @DisplayName("整改开始后旧方案提交重放和旧任务均不能绕过本轮要求")
    void oldCycleCannotSubmit() {
        Ref r=completed(); var command=submit(r);
        Proposal p=proposal(r,ok(post(resolutions(r),command,engineer,"original"),201));
        var rejected=ok(decide(p,"REJECT","未解决",customer,key()),200);
        code(post(resolutions(r),command,engineer,"original"),404,"RESOURCE_FORBIDDEN");
        Ref next=assign(new Ref(r.ticket(),rejected.path("remediationTaskId").asLong(),rejected.path("ticketVersion").asInt(),0),5);
        command.put("expectedTicketVersion",next.ticketVersion());
        code(post(resolutions(r),command,engineer,key()),404,"RESOURCE_FORBIDDEN");
        code(post(resolutions(next),submit(next),engineer,key()),422,"INVALID_STATE_TRANSITION");
        code(post("/tickets/"+r.ticket()+"/tasks",Map.of("expectedTicketVersion",next.ticketVersion(),"title","任意新建"),manager,key()),422,"INVALID_STATE_TRANSITION");
        assertThat(count("ticket_resolution")).isEqualTo(1);
    }

    @Test @DisplayName("双版本、未知字段、空方案和拒绝理由校验不产生副作用")
    void validationAndVersions() {
        Ref r=completed();
        var c=submit(r); c.put("expectedTaskVersion",0);
        code(post(resolutions(r),c,engineer,key()),409,"TASK_VERSION_CONFLICT");
        c=submit(r); c.put("expectedTicketVersion",0);
        code(post(resolutions(r),c,engineer,key()),409,"VERSION_CONFLICT");
        c=submit(r); c.put("summary"," ");
        code(post(resolutions(r),c,engineer,key()),400,"REQUEST_INVALID");
        c=submit(r); c.put("resolutionCycleNo",2);
        code(post(resolutions(r),c,engineer,key()),400,"REQUEST_INVALID");
        code(post(resolutions(r),submit(r),engineer,null),400,"IDEMPOTENCY_KEY_REQUIRED");
        Proposal p=propose(r);
        code(decide(p,"REJECT"," ",customer,key()),422,"REJECTION_REASON_REQUIRED");
        code(decide(p,"REJECT",null,customer,key()),422,"REJECTION_REASON_REQUIRED");
        code(decide(p,"OTHER",null,customer,key()),400,"REQUEST_INVALID");
        code(post(acceptance(p),Map.of("expectedTicketVersion",0,"decision","ACCEPT"),customer,key()),409,"VERSION_CONFLICT");
        assertThat(count("ticket_acceptance")).isZero();
        assertThat(count("service_task")).isEqualTo(1);
    }

    @Test @DisplayName("方案与验收同键重放只写一次，改正文或资源冲突，重放仍检查客户权限")
    void idempotencyAndRevocation() {
        Ref r=completed(); var command=submit(r);
        JsonNode first=ok(post(resolutions(r),command,engineer,"submit"),201);
        assertThat(ok(post(resolutions(r),command,engineer,"submit"),201)).isEqualTo(first);
        Proposal p=proposal(r,first);
        command.put("summary","另一个方案");
        code(post(resolutions(r),command,engineer,"submit"),409,"IDEMPOTENCY_KEY_CONFLICT");
        var accepted=ok(decide(p,"ACCEPT",null,customer,"decision"),200);
        assertThat(ok(decide(p,"ACCEPT",null,customer,"decision"),200)).isEqualTo(accepted);
        assertThat(ok(post(resolutions(r),submit(r),engineer,"submit"),201)).isEqualTo(first);
        code(decide(p,"REJECT","修改决定",customer,"decision"),409,"IDEMPOTENCY_KEY_CONFLICT");
        Ref other=completed();
        code(post(resolutions(other),submit(other),engineer,"submit"),409,"IDEMPOTENCY_KEY_CONFLICT");
        Proposal otherP=propose(other);
        code(decide(otherP,"ACCEPT",null,customer,"decision"),409,"IDEMPOTENCY_KEY_CONFLICT");
        jdbc.update("DELETE FROM customer_user WHERE customer_id=1 AND user_id=1");
        code(decide(p,"ACCEPT",null,customer,"decision"),404,"RESOURCE_FORBIDDEN");
        assertThat(count("ticket_acceptance")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"submit","ACCEPT","REJECT","reject-task"})
    @DisplayName("注入后段失败，方案、验收、整改、SLA、活动、版本和幂等全部回滚")
    void atomicRollback(String operation) {
        Ref r=completed();
        Proposal p=operation.equals("submit")?null:propose(r);
        var before=snapshot();
        String target=operation.equals("reject-task")?"service_task":"business_audit_log";
        jdbc.execute("CREATE TRIGGER resolution_failure BEFORE INSERT ON "+target+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='resolution rollback probe'");
        try {
            var response=operation.equals("submit")?post(resolutions(r),submit(r),engineer,"rollback"):
                    decide(p,operation.equals("ACCEPT")?"ACCEPT":"REJECT","整改原因",customer,"rollback");
            code(response,500,"INTERNAL_ERROR");
            assertThat(snapshot()).isEqualTo(before);
        } finally { jdbc.execute("DROP TRIGGER resolution_failure"); }
        if(operation.equals("submit")) ok(post(resolutions(r),submit(r),engineer,"rollback"),201);
        else ok(decide(p,operation.equals("ACCEPT")?"ACCEPT":"REJECT","整改原因",customer,"rollback"),200);
    }

    @Test @DisplayName("历史方案不能二次验收，关闭工单不能再写消息")
    void terminalAndOldResolutionDecisions() {
        Proposal p=propose(completed());
        var rejected=ok(decide(p,"REJECT","整改",customer,"reject"),200);
        Ref next=work(new Ref(p.ref().ticket(),rejected.path("remediationTaskId").asLong(),rejected.path("ticketVersion").asInt(),0),5,engineer);
        Proposal second=propose(next);
        code(post(acceptance(p),Map.of("expectedTicketVersion",second.version(),"decision","ACCEPT"),customer,key()),422,"INVALID_STATE_TRANSITION");
        ok(decide(second,"ACCEPT",null,customer,key()),200);
        code(post(acceptance(second),Map.of("expectedTicketVersion",second.version()+1,"decision","ACCEPT"),customer,key()),422,"INVALID_STATE_TRANSITION");
        code(post("/tickets/"+next.ticket()+"/messages",Map.of("expectedTicketVersion",second.version()+1,"content","关闭后写入"),engineer,key()),422,"INVALID_STATE_TRANSITION");
        assertThat(count("ticket_acceptance")).isEqualTo(2);
    }

    @Test @DisplayName("数据库限制整改来源唯一且不能引用另一工单方案")
    void remediationDatabaseConstraints() {
        Proposal p=propose(completed());
        ok(decide(p,"REJECT","整改",customer,key()),200);
        Ref other=completed();
        String sql="INSERT INTO service_task(id,ticket_id,title,is_blocking,status,version,created_by,created_at,resolution_cycle_no,source_resolution_id) VALUES(999001,?,'非法整改',1,'CANCELLED',0,4,NOW(3),2,?)";
        assertThatThrownBy(()->jdbc.update(sql,p.ref().ticket(),p.id())).isInstanceOf(DataIntegrityViolationException.class);
        Proposal otherP=propose(other);
        assertThatThrownBy(()->jdbc.update(sql,p.ref().ticket(),otherP.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("service_task")).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("双连接并发提交方案，同键重放或异键版本冲突")
    void concurrentSubmission(boolean sameKey) throws Exception {
        Ref r=completed();
        var results=race(()->post(resolutions(r),submit(r),engineer,"submit-a"),
                ()->post(resolutions(r),submit(r),engineer,sameKey?"submit-a":"submit-b"));
        statuses(results,201,sameKey?201:409);
        if(sameKey) assertThat(results.get(0).getBody()).isEqualTo(results.get(1).getBody());
        assertThat(count("ticket_resolution")).isEqualTo(1);
        assertThat(count("ticket_sla_event")).isEqualTo(2);
    }

    @Test @DisplayName("双连接同意与拒绝竞争只能产生一种决定")
    void concurrentAcceptanceAndRejection() throws Exception {
        Proposal p=propose(completed());
        var results=race(()->decide(p,"ACCEPT",null,customer,"accept"),()->decide(p,"REJECT","仍有故障",customer,"reject"));
        statuses(results,200,409);
        String decision=jdbc.queryForObject("SELECT decision FROM ticket_acceptance",String.class);
        assertThat(count("ticket_acceptance")).isEqualTo(1);
        assertThat(count("service_task")).isEqualTo(decision.equals("REJECT")?2:1);
        assertThat(sla(p.ref().ticket()).get("resolution_clock_state")).isEqualTo(decision.equals("REJECT")?"RUNNING":"CLOSED");
    }

    @Test @DisplayName("双连接同键拒绝只创建一个整改任务和一个恢复事件")
    void concurrentRejectionReplay() throws Exception {
        Proposal p=propose(completed());
        var results=race(()->decide(p,"REJECT","整改",customer,"reject"),()->decide(p,"REJECT","整改",customer,"reject"));
        statuses(results,200,200);
        assertThat(results.get(0).getBody()).isEqualTo(results.get(1).getBody());
        assertThat(count("service_task")).isEqualTo(2);
        assertThat(count("ticket_sla_event")).isEqualTo(3);
    }

    @Test @DisplayName("方案提交与公开答复共享工单版本和事务锁")
    void submissionAndPublicResponseCompete() throws Exception {
        Ref r=completed(); clock.advance(1000);
        var results=race(()->post(resolutions(r),submit(r),engineer,"submit"),
                ()->post("/tickets/"+r.ticket()+"/messages",Map.of("expectedTicketVersion",r.ticketVersion(),"content","公开答复"),support,"message"));
        statuses(results,201,409);
        assertThat(count("ticket_resolution")).isEqualTo(results.get(0).getStatusCode().value()==201?1:0);
        assertThat(sla(r.ticket()).get("first_responded_at")==null).isEqualTo(results.get(0).getStatusCode().value()==201);
    }

    @Test @DisplayName("拒绝先持锁提交后，等待锁的旧轮次工程师重放被撤权")
    void rejectionWinsAgainstOldSubmissionReplay() throws Exception {
        Ref r=completed(); Proposal p=proposal(r,ok(post(resolutions(r),submit(r),engineer,"submit"),201));
        var gate=probe.use(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->decide(p,"REJECT","整改",customer,"reject"));
            assertThat(gate.held.await(10,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->post(resolutions(r),submit(r),engineer,"submit"));
            try { assertThat(gate.waiter.await(10,TimeUnit.SECONDS)).isTrue(); }
            finally { gate.release.countDown(); }
            ok(a.get(20,TimeUnit.SECONDS),200); code(b.get(20,TimeUnit.SECONDS),404,"RESOURCE_FORBIDDEN");
            assertThat(gate.connections).hasSize(2);
        } finally { probe.clear(); }
        assertThat(count("service_task")).isEqualTo(2);
    }

    private List<ResponseEntity<JsonNode>> race(Callable<ResponseEntity<JsonNode>> a, Callable<ResponseEntity<JsonNode>> b) throws Exception {
        var gate=probe.use(0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(a); var second=pool.submit(b);
            var result=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(gate.connections).hasSize(2); return result;
        } finally { probe.clear(); }
    }
    private Ref created() {
        long id=ok(post("/tickets",Map.of("customerId",1,"contractId",1,"title","数据库连接故障","description","连接超时",
                "serviceType","DATABASE_SUPPORT","impact","HIGH","urgency","HIGH"),customer,key()),201).path("ticketId").asLong();
        ok(post("/tickets/"+id+"/triage",Map.of("expectedTicketVersion",0,"confirmedCategory","DATABASE_CONNECTION",
                "confirmedFacts",List.of(),"confirmedFollowups",List.of(),"confirmedDiagnostics",List.of()),support,key()),200);
        return ref(ok(post("/tickets/"+id+"/tasks",Map.of("expectedTicketVersion",1,"title","诊断"),manager,key()),201));
    }
    private Ref completed() { return work(created(),5,engineer); }
    private Ref work(Ref r,long owner,String token) { return finish(assign(r,owner),token); }
    private Ref assign(Ref r,long owner) {
        var c=versions(r); c.put("assigneeId",owner); c.put("reason","安排处理");
        return ref(ok(post(task(r,"assignments"),c,manager,key()),200));
    }
    private Ref finish(Ref r,String token) {
        r=ref(ok(post(task(r,"accept"),versions(r),token,key()),200));
        r=ref(ok(post(task(r,"start"),versions(r),token,key()),200));
        var c=versions(r); c.put("result","内部处理记录，不向客户公开");
        return ref(ok(post(task(r,"complete"),c,token,key()),200));
    }
    private Proposal propose(Ref r) { return propose(r,engineer); }
    private Proposal propose(Ref r,String token) { return proposal(r,ok(post(resolutions(r),submit(r),token,key()),201)); }
    private Proposal proposal(Ref r,JsonNode b) { return new Proposal(r,b.path("resolutionId").asLong(),b.path("ticketVersion").asInt()); }
    private Map<String,Object> submit(Ref r) {
        var c=versions(r); c.put("taskId",r.task()); c.put("summary","已修复连接池配置，请验证");
        return c;
    }
    private ResponseEntity<JsonNode> decide(Proposal p,String decision,String reason,String token,String key) {
        var c=new LinkedHashMap<String,Object>(); c.put("expectedTicketVersion",p.version()); c.put("decision",decision); c.put("reason",reason);
        return post(acceptance(p),c,token,key);
    }
    private String acceptance(Proposal p) { return "/resolutions/"+p.id()+"/acceptance"; }
    private String resolutions(Ref r) { return "/tickets/"+r.ticket()+"/resolutions"; }
    private String task(Ref r,String op) { return "/tasks/"+r.task()+"/"+op; }
    private Map<String,Object> versions(Ref r) { return new LinkedHashMap<>(Map.of("expectedTicketVersion",r.ticketVersion(),"expectedTaskVersion",r.taskVersion())); }
    private Ref ref(JsonNode b) { return new Ref(b.path("ticketId").asLong(),b.path("taskId").asLong(),b.path("ticketVersion").asInt(),b.path("taskVersion").asInt()); }
    private Map<String,Object> sla(long ticket) { return jdbc.queryForMap("SELECT * FROM ticket_sla_instance WHERE ticket_id=?",ticket); }
    private Map<String,Object> snapshot() {
        var data=new LinkedHashMap<String,Object>();
        for(String table:List.of("service_ticket","service_task","ticket_resolution","ticket_acceptance","ticket_sla_instance","ticket_sla_event",
                "ticket_activity","task_assignment_history","business_audit_log","idempotency_request"))
            data.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        return data;
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private JsonNode ok(ResponseEntity<JsonNode> r,int status) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(status); return r.getBody();
    }
    private void code(ResponseEntity<JsonNode> r,int status,String code) { assertThat(ok(r,status).path("code").asText()).isEqualTo(code); }
    private void statuses(List<ResponseEntity<JsonNode>> r,int... expected) {
        assertThat(r.stream().mapToInt(x->x.getStatusCode().value()).sorted().toArray()).containsExactly(Arrays.stream(expected).sorted().toArray());
    }
    private String key() { return UUID.randomUUID().toString(); }
    private String login(String username) {
        return ok(post("/auth/login",Map.of("username",username,"password","S1Demo!2026"),null,null),200).path("token").asText();
    }
    private ResponseEntity<JsonNode> post(String path,Object body,String token,String key) { return request(HttpMethod.POST,path,body,token,key); }
    private ResponseEntity<JsonNode> get(String path,String token) { return request(HttpMethod.GET,path,null,token,null); }
    private ResponseEntity<JsonNode> request(HttpMethod method,String path,Object body,String token,String key) {
        var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.setBearerAuth(token);
        if(key!=null) headers.set("Idempotency-Key",key);
        return http.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
}
