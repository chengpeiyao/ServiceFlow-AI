package com.serviceflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketOperationsIntegrationTest extends MySqlTestSupport {
    @TestConfiguration static class Environment {
        @Bean @Primary ResolutionIntegrationTest.MutableClock testClock() { return new ResolutionIntegrationTest.MutableClock(); }
        @Bean S2IntegrationTest.TaskLockProbe taskLockProbe() { return new S2IntegrationTest.TaskLockProbe(); }
        @Bean CountProbe countProbe() { return new CountProbe(); }
    }
    // 在真实 COUNT 查询返回后等待另一连接提交，验证总数和列表的读取快照一致。
    @Intercepts(@Signature(type=Executor.class, method="query",
            args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class CountProbe implements Interceptor {
        final AtomicReference<CountGate> active = new AtomicReference<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var gate=active.get();
            Object result=invocation.proceed();
            if(gate!=null && ((MappedStatement)invocation.getArgs()[0]).getId().equals("com.serviceflow.ticket.TicketMapper.countVisible")) {
                try(var statement=((Executor)invocation.getTarget()).getTransaction().getConnection().createStatement();
                    var row=statement.executeQuery("SELECT CONNECTION_ID()")) { row.next(); gate.connection=row.getLong(1); }
                gate.counted.countDown();
                if(!gate.release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("分页快照测试未释放");
            }
            return result;
        }
        void clear() { var gate=active.getAndSet(null); if(gate!=null) gate.release.countDown(); }
    }
    static class CountGate {
        final CountDownLatch counted=new CountDownLatch(1), release=new CountDownLatch(1);
        volatile long connection;
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResolutionIntegrationTest.MutableClock clock;
    @Autowired S2IntegrationTest.TaskLockProbe probe;
    @Autowired CountProbe countProbe;
    String customer, other, support, manager, engineer, backup;
    record Ref(long ticket, long task, int ticketVersion, int taskVersion) {}

    @BeforeEach void reset() {
        clean(); clock.time.set(ResolutionIntegrationTest.START);
        customer=login("customer_a"); other=login("customer_b"); support=login("support");
        manager=login("manager"); engineer=login("engineer"); backup=login("engineer_backup");
    }
    @AfterEach void clean() {
        probe.clear(); countProbe.clear();
        jdbc.execute("DROP TRIGGER IF EXISTS cancellation_failure");
        for(String table:List.of("ticket_triage_record","ai_triage_proposal","ai_triage_run","ticket_activity",
                "ticket_acceptance","task_assignment_history","service_task","ticket_resolution",
                "ticket_sla_event","ticket_sla_instance","business_audit_log","service_ticket","idempotency_request"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE app_user SET account_status='ACTIVE'");
        jdbc.update("DELETE FROM customer_user WHERE customer_id=1 AND user_id=2");
        jdbc.update("INSERT IGNORE INTO customer_user(customer_id,user_id) VALUES(1,1)");
        jdbc.update("INSERT IGNORE INTO team_manager_scope(team_id,manager_user_id) VALUES(1,4)");
        jdbc.update("UPDATE sla_policy_binding SET active_policy_version_id=1 WHERE service_type='DATABASE_SUPPORT' AND priority='P1'");
    }

    @ParameterizedTest @ValueSource(strings={"SUBMITTED","TRIAGED"})
    @DisplayName("创建者合法取消，停止计时、保留原文和取消原因")
    void cancelEligibleStages(String stage) {
        long id=create(customer,1,"数据库故障");
        int version=stage.equals("TRIAGED")?triage(id):0;
        clock.advance(1250);
        var body=ok(cancel(id,version,customer,"cancel"),200);
        assertThat(body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.path("ticketVersion").asInt()).isEqualTo(version+1);
        assertThat(Instant.parse(body.path("cancelledAt").asText())).isEqualTo(clock.instant());
        var sla=sla(id);
        assertThat(sla.get("resolution_clock_state")).isEqualTo("CANCELLED");
        assertThat(sla.get("resolution_elapsed_millis")).isEqualTo(1250L);
        assertThat(sla.get("resolution_elapsed_seconds")).isEqualTo(1L);
        for(String field:List.of("resolution_met","response_met","first_responded_at","resolution_last_started_at","resolution_due_at"))
            assertThat(sla.get(field)).as(field).isNull();
        var detail=ok(get("/tickets/"+id,customer),200);
        assertThat(detail.path("ticket").path("originalDescription").asText()).isEqualTo("不可覆盖的客户原文");
        assertThat(detail.path("ticket").path("closedAt").isNull()).isTrue();
        assertThat(detail.path("activities").get(0).path("body").asText()).isEqualTo("客户撤回报障");
        assertThat(jdbc.queryForObject("SELECT reason FROM ticket_sla_event WHERE event_type='CANCELLED'",String.class)).isEqualTo("客户撤回报障");
        assertThat(count("ticket_sla_event")).isEqualTo(2);
        assertThat(count("service_task")).isZero();
    }

    @ParameterizedTest @ValueSource(longs={1000,3600001})
    @DisplayName("客服可取消工单，已记录的首响时间和结果不被改写")
    void cancellationPreservesFirstResponse(long elapsed) {
        long id=create(customer,1,"故障"); clock.advance(elapsed);
        ok(post("/tickets/"+id+"/messages",Map.of("expectedTicketVersion",0,"content","公开回复"),support,key()),201);
        var before=sla(id); clock.advance(2000);
        ok(cancel(id,1,support,key()),200);
        assertThat(sla(id).get("first_responded_at")).isEqualTo(before.get("first_responded_at"));
        assertThat(sla(id).get("response_met")).isEqualTo(before.get("response_met"));
        assertThat(sla(id).get("resolution_met")).isNull();
    }

    @Test @DisplayName("同客户非创建者、跨客户和其他内部角色均不能取消")
    void cancellationAuthorization() {
        long id=create(customer,1,"故障");
        code(cancel(id,0,other,key()),404,"RESOURCE_FORBIDDEN");
        jdbc.update("INSERT INTO customer_user(customer_id,user_id) VALUES(1,2)");
        ok(get("/tickets/"+id,other),200);
        code(cancel(id,0,other,key()),404,"RESOURCE_FORBIDDEN");
        for(String token:List.of(manager,engineer,login("ops")))
            code(cancel(id,0,token,key()),403,"RESOURCE_FORBIDDEN");
        code(cancel(999999,0,customer,key()),404,"RESOURCE_FORBIDDEN");
        assertThat(count("ticket_activity")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM service_ticket WHERE id=?",String.class,id)).isEqualTo("SUBMITTED");
    }

    @Test @DisplayName("存在未派任务先拒绝；主管取消任务后工单方可取消")
    void taskMustBeCancelledFirst() {
        Ref r=created();
        code(cancel(r.ticket(),r.ticketVersion(),customer,key()),409,"ACTIVE_TASK_EXISTS");
        var c=versions(r); c.put("reason","无需派工");
        Ref stopped=ref(ok(post(task(r,"cancel"),c,manager,key()),200));
        ok(cancel(stopped.ticket(),stopped.ticketVersion(),customer,key()),200);
        assertThat(jdbc.queryForObject("SELECT status FROM service_task WHERE id=?",String.class,r.task())).isEqualTo("CANCELLED");
        assertThat(count("service_task")).isEqualTo(1);
        code(post("/tickets/"+r.ticket()+"/tasks",Map.of("expectedTicketVersion",stopped.ticketVersion()+1,"title","取消后新增"),manager,key()),422,"INVALID_STATE_TRANSITION");
    }

    @ParameterizedTest @ValueSource(strings={"ASSIGNED","IN_PROGRESS","PENDING_ACCEPTANCE","CLOSED","CANCELLED"})
    @DisplayName("已派发、处理中、待验收和终态均禁止主动取消")
    void illegalCancellationStates(String state) {
        long id=create(customer,1,"故障");
        jdbc.update("UPDATE service_ticket SET status=? WHERE id=?",state,id);
        var before=snapshot();
        code(cancel(id,0,support,key()),422,"INVALID_STATE_TRANSITION");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test @DisplayName("取消校验版本、原因、额外字段和请求键，终态不能再追加消息")
    void cancellationValidation() {
        long id=create(customer,1,"故障");
        code(cancel(id,1,customer,key()),409,"VERSION_CONFLICT");
        code(cancel(id,0,customer,null),400,"IDEMPOTENCY_KEY_REQUIRED");
        for(String reason:List.of(" ","字".repeat(501)))
            code(post("/tickets/"+id+"/cancel",Map.of("expectedTicketVersion",0,"reason",reason),customer,key()),400,"REQUEST_INVALID");
        code(post("/tickets/"+id+"/cancel",Map.of("expectedTicketVersion",0,"reason","撤回","status","CLOSED"),customer,key()),400,"REQUEST_INVALID");
        ok(cancel(id,0,customer,key()),200);
        code(post("/tickets/"+id+"/messages",Map.of("expectedTicketVersion",1,"content","不应写入"),support,key()),422,"INVALID_STATE_TRANSITION");
        code(post("/tickets/"+id+"/triage",triageCommand(1),support,key()),422,"INVALID_STATE_TRANSITION");
    }

    @Test @DisplayName("取消幂等重放不重复副作用，改正文或资源冲突，撤权后不得重放")
    void cancellationReplayAndRevocation() {
        long id=create(customer,1,"故障");
        var first=ok(cancel(id,0,customer,"cancel"),200); var before=snapshot();
        clock.advance(5000);
        assertThat(ok(cancel(id,0,customer,"cancel"),200)).isEqualTo(first);
        assertThat(snapshot()).isEqualTo(before);
        code(post("/tickets/"+id+"/cancel",Map.of("expectedTicketVersion",0,"reason","其他理由"),customer,"cancel"),409,"IDEMPOTENCY_KEY_CONFLICT");
        long another=create(customer,1,"另一个故障");
        code(cancel(another,0,customer,"cancel"),409,"IDEMPOTENCY_KEY_CONFLICT");
        jdbc.update("DELETE FROM customer_user WHERE customer_id=1 AND user_id=1");
        code(cancel(id,0,customer,"cancel"),404,"RESOURCE_FORBIDDEN");
    }

    @ParameterizedTest @ValueSource(strings={"business_audit_log","ticket_sla_event","ticket_activity"})
    @DisplayName("取消事务任一后段失败，工单、SLA、事件、活动、审计和幂等均回滚")
    void cancellationRollback(String table) {
        long id=create(customer,1,"故障"); clock.advance(999);
        var before=snapshot();
        jdbc.execute("CREATE TRIGGER cancellation_failure BEFORE INSERT ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cancellation rollback probe'");
        try {
            code(cancel(id,0,customer,"rollback"),500,"INTERNAL_ERROR");
            assertThat(snapshot()).isEqualTo(before);
        } finally { jdbc.execute("DROP TRIGGER cancellation_failure"); }
        ok(cancel(id,0,customer,"rollback"),200);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("双连接取消，同键重放或异键版本冲突，只写一次事件")
    void concurrentCancellation(boolean sameKey) throws Exception {
        long id=create(customer,1,"故障");
        var results=race(()->cancel(id,0,customer,"cancel-a"),()->cancel(id,0,customer,sameKey?"cancel-a":"cancel-b"));
        statuses(results,200,sameKey?200:409);
        if(sameKey) assertThat(results.get(0).getBody()).isEqualTo(results.get(1).getBody());
        assertThat(count("ticket_sla_event")).isEqualTo(2);
        assertThat(count("ticket_activity")).isEqualTo(1);
    }

    @Test @DisplayName("取消与新建任务竞争，不能留下已取消工单的活动任务")
    void cancellationAndTaskCreationCompete() throws Exception {
        long id=create(customer,1,"故障"); triage(id);
        var results=race(()->cancel(id,1,customer,"cancel"),
                ()->post("/tickets/"+id+"/tasks",Map.of("expectedTicketVersion",1,"title","诊断"),manager,"create-task"));
        assertThat(results.stream().filter(r->r.getStatusCode().value()==409).count()).isEqualTo(1);
        boolean cancelled=results.get(0).getStatusCode().value()==200;
        assertThat(count("service_task")).isEqualTo(cancelled?0:1);
        assertThat(sla(id).get("resolution_clock_state")).isEqualTo(cancelled?"CANCELLED":"RUNNING");
        if(!cancelled) code(cancel(id,2,customer,key()),409,"ACTIVE_TASK_EXISTS");
    }

    @ParameterizedTest @ValueSource(strings={"triage","messages"})
    @DisplayName("取消与受理或公开答复竞争，只有一个旧版本请求成功")
    void cancellationCompetesWithOtherWrites(String operation) throws Exception {
        long id=create(customer,1,"故障");
        Object body=operation.equals("triage")?triageCommand(0):Map.of("expectedTicketVersion",0,"content","公开答复");
        var results=race(()->cancel(id,0,customer,"cancel"),()->post("/tickets/"+id+"/"+operation,body,support,"other"));
        assertThat(results.stream().filter(r->r.getStatusCode().value()==409).count()).isEqualTo(1);
        boolean cancelled=results.get(0).getStatusCode().value()==200;
        if(cancelled) {
            assertThat(count("ticket_triage_record")).isZero();
            assertThat(sla(id).get("first_responded_at")).isNull();
        } else ok(cancel(id,1,customer,key()),200);
        assertThat(sla(id).get("resolution_clock_state")).isEqualTo("CANCELLED");
    }

    @Test @DisplayName("分页按创建时间和 ID 倒序，跨页不重漏，默认值和空页明确")
    void paginationAndStableOrder() {
        long first=create(customer,1,"故障一"), second=create(customer,1,"故障二"), third=create(customer,1,"故障三");
        var defaults=page("",customer);
        assertThat(defaults.path("page").asInt()).isEqualTo(1);
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(defaults.path("total").isTextual()).isTrue();
        assertThat(ids(defaults)).containsExactly(third,second,first);
        assertThat(ids(page("?size=2&page=1",customer))).containsExactly(third,second);
        assertThat(ids(page("?size=2&page=2",customer))).containsExactly(first);
        var empty=page("?page=3&size=2",customer);
        assertThat(ids(empty)).isEmpty(); assertThat(empty.path("total").asLong()).isEqualTo(3);
        assertThat(defaults.path("items").get(0).has("originalDescription")).isFalse();
        assertThat(defaults.path("items").get(0).has("activities")).isFalse();
        // 创建时间优先于雪花 ID，而不是碰巧依赖 ID 的自然顺序。
        jdbc.update("UPDATE service_ticket SET created_at=DATE_ADD(created_at,INTERVAL 1 SECOND) WHERE id=?",first);
        assertThat(ids(page("",customer))).containsExactly(first,third,second);
    }

    @Test @DisplayName("列表和总数均执行客户、团队和内部角色范围过滤")
    void queryScopeAndFilterIntersection() {
        long a=create(customer,1,"客户一工单"), b=create(other,2,"客户二工单");
        jdbc.update("UPDATE service_ticket SET owner_team_id=2 WHERE id=?",b);
        assertThat(ids(page("",customer))).containsExactly(a);
        assertThat(page("?customerId=2",customer).path("total").asLong()).isZero();
        assertThat(ids(page("?customerId=2",customer))).isEmpty();
        assertThat(ids(page("",other))).containsExactly(b);
        assertThat(ids(page("",manager))).containsExactly(a);
        assertThat(page("?ownerTeamId=2",manager).path("total").asLong()).isZero();
        assertThat(ids(page("",login("manager_other")))).containsExactly(b);
        assertThat(ids(page("",support))).containsExactly(b,a);
        assertThat(ids(page("",login("ops")))).containsExactly(b,a);
        jdbc.update("INSERT INTO customer_user(customer_id,user_id) VALUES(1,2)");
        assertThat(ids(page("",other))).containsExactly(b,a);
        jdbc.update("DELETE FROM team_manager_scope WHERE team_id=1 AND manager_user_id=4");
        assertThat(page("",manager).path("total").asLong()).isZero();
        assertThat(page("",engineer).path("total").asLong()).isZero();
    }

    @Test @DisplayName("筛选条件取交集，标题通配符按普通字符匹配，不执行 SQL 片段")
    void filtersAndLiteralKeyword() {
        long a=create(customer,1,"db_100% failure"), b=create(customer,1,"normal failure");
        triage(a); ok(cancel(b,0,customer,key()),200);
        assertThat(ids(page("?status=TRIAGED&priority=P1&customerId=1&ownerTeamId=1&keyword=db_100%25",customer))).containsExactly(a);
        assertThat(page("?status=TRIAGED&priority=P2",customer).path("total").asLong()).isZero();
        assertThat(ids(page("?status=CANCELLED",customer))).containsExactly(b);
        assertThat(ids(page("?keyword=_",customer))).containsExactly(a);
        assertThat(ids(page("?keyword=%25",customer))).containsExactly(a);
        assertThat(page("?keyword=%27%20OR%201%3D1%20--",customer).path("total").asLong()).isZero();
        assertThat(ids(page("?keyword=%20%20",customer))).containsExactly(b,a);
        assertThat(ids(page("?role=OPS_ADMIN&actorId=2",customer))).containsExactly(b,a);
    }

    @ParameterizedTest @ValueSource(strings={"page=0","page=-1","page=1000001","page=2147483648","page=abc","size=0","size=101","status=UNKNOWN","priority=P4","customerId=0","ownerTeamId=-1"})
    @DisplayName("分页参数越界或类型错误返回 400，不执行无限制查询")
    void invalidQuery(String query) { code(get("/tickets?"+query,customer),400,"REQUEST_INVALID"); }

    @Test @DisplayName("列表要求有效登录，账号停用后拒绝，过长关键字返回 400")
    void queryLoginAndLength() {
        code(get("/tickets",null),401,"UNAUTHENTICATED");
        code(get("/tickets?keyword="+"a".repeat(121),customer),400,"REQUEST_INVALID");
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=1");
        code(get("/tickets",customer),401,"UNAUTHENTICATED");
    }

    @Test @DisplayName("工程师列表随转派、完成、验收和整改轮次更新，旧负责人失权")
    void engineerListFollowsOwnershipAndCycle() {
        Ref r=assign(created(),5);
        assertThat(ids(page("",engineer))).containsExactly(r.ticket());
        var transfer=versions(r); transfer.put("assigneeId",8); transfer.put("expectedAssigneeId",5); transfer.put("reason","交接");
        r=ref(ok(post(task(r,"reassignments"),transfer,manager,key()),200));
        assertThat(ids(page("",engineer))).isEmpty();
        assertThat(ids(page("",backup))).containsExactly(r.ticket());
        r=ref(ok(post(task(r,"accept"),versions(r),backup,key()),200));
        r=ref(ok(post(task(r,"start"),versions(r),backup,key()),200));
        var complete=versions(r); complete.put("result","内部完成记录");
        r=ref(ok(post(task(r,"complete"),complete,backup,key()),200));
        assertThat(ids(page("?status=IN_PROGRESS",backup))).containsExactly(r.ticket());
        var submit=versions(r); submit.put("taskId",r.task()); submit.put("summary","请验收");
        var proposal=ok(post("/tickets/"+r.ticket()+"/resolutions",submit,backup,key()),201);
        assertThat(ids(page("?status=PENDING_ACCEPTANCE",backup))).containsExactly(r.ticket());
        var decision=ok(post("/resolutions/"+proposal.path("resolutionId").asLong()+"/acceptance",
                Map.of("expectedTicketVersion",proposal.path("ticketVersion").asInt(),"decision","REJECT","reason","继续整改"),customer,key()),200);
        assertThat(ids(page("",backup))).isEmpty();
        Ref rework=assign(new Ref(r.ticket(),decision.path("remediationTaskId").asLong(),decision.path("ticketVersion").asInt(),0),5);
        assertThat(ids(page("",engineer))).containsExactly(rework.ticket());
        assertThat(ids(page("",backup))).isEmpty();
    }

    @Test @DisplayName("COUNT 与取页之间另一连接提交变更，当前响应仍来自同一快照")
    void countAndItemsShareSnapshot() throws Exception {
        long id=create(customer,1,"故障"); var gate=new CountGate(); countProbe.active.set(gate);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var response=pool.submit(()->get("/tickets?status=SUBMITTED",customer));
            assertThat(gate.counted.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                jdbc.execute((ConnectionCallback<Void>) connection->{
                    try(var statement=connection.createStatement(); var row=statement.executeQuery("SELECT CONNECTION_ID()")) {
                        row.next(); assertThat(row.getLong(1)).isNotEqualTo(gate.connection);
                    }
                    try(var statement=connection.prepareStatement("UPDATE service_ticket SET status='TRIAGED' WHERE id=?")) {
                        statement.setLong(1,id); assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    return null;
                });
            } finally { gate.release.countDown(); }
            var result=ok(response.get(20,TimeUnit.SECONDS),200);
            assertThat(result.path("total").asLong()).isEqualTo(1);
            assertThat(ids(result)).containsExactly(id);
            assertThat(result.path("items").get(0).path("status").asText()).isEqualTo("SUBMITTED");
        } finally { countProbe.clear(); }
        assertThat(page("?status=SUBMITTED",customer).path("total").asLong()).isZero();
    }

    private List<ResponseEntity<JsonNode>> race(Callable<ResponseEntity<JsonNode>> a,Callable<ResponseEntity<JsonNode>> b) throws Exception {
        var gate=probe.use(0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(a); var second=pool.submit(b);
            var results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(gate.connections).hasSize(2); return results;
        } finally { probe.clear(); }
    }
    private long create(String token,int customerId,String title) {
        return ok(post("/tickets",Map.of("customerId",customerId,"contractId",customerId,"title",title,"description","不可覆盖的客户原文",
                "serviceType","DATABASE_SUPPORT","impact","HIGH","urgency","HIGH"),token,key()),201).path("ticketId").asLong();
    }
    private Map<String,Object> triageCommand(int version) {
        return Map.of("expectedTicketVersion",version,"confirmedCategory","DATABASE_CONNECTION","confirmedFacts",List.of(),"confirmedFollowups",List.of(),"confirmedDiagnostics",List.of());
    }
    private int triage(long id) { return ok(post("/tickets/"+id+"/triage",triageCommand(0),support,key()),200).path("ticketVersion").asInt(); }
    private Ref created() {
        long id=create(customer,1,"故障"); triage(id);
        return ref(ok(post("/tickets/"+id+"/tasks",Map.of("expectedTicketVersion",1,"title","诊断"),manager,key()),201));
    }
    private Ref assign(Ref r,long owner) {
        var c=versions(r); c.put("assigneeId",owner); c.put("reason","安排处理");
        return ref(ok(post(task(r,"assignments"),c,manager,key()),200));
    }
    private Map<String,Object> versions(Ref r) { return new LinkedHashMap<>(Map.of("expectedTicketVersion",r.ticketVersion(),"expectedTaskVersion",r.taskVersion())); }
    private String task(Ref r,String operation) { return "/tasks/"+r.task()+"/"+operation; }
    private Ref ref(JsonNode b) { return new Ref(b.path("ticketId").asLong(),b.path("taskId").asLong(),b.path("ticketVersion").asInt(),b.path("taskVersion").asInt()); }
    private ResponseEntity<JsonNode> cancel(long id,int version,String token,String key) { return post("/tickets/"+id+"/cancel",Map.of("expectedTicketVersion",version,"reason","客户撤回报障"),token,key); }
    private JsonNode page(String query,String token) { return ok(get("/tickets"+query,token),200); }
    private List<Long> ids(JsonNode page) { var result=new ArrayList<Long>(); page.path("items").forEach(x->result.add(x.path("id").asLong())); return result; }
    private Map<String,Object> sla(long id) { return jdbc.queryForMap("SELECT * FROM ticket_sla_instance WHERE ticket_id=?",id); }
    private Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();
        for(String table:List.of("service_ticket","service_task","ticket_sla_instance","ticket_sla_event","ticket_activity","business_audit_log","idempotency_request"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        return result;
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private JsonNode ok(ResponseEntity<JsonNode> r,int status) { assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(status); return r.getBody(); }
    private void code(ResponseEntity<JsonNode> r,int status,String code) { assertThat(ok(r,status).path("code").asText()).isEqualTo(code); }
    private void statuses(List<ResponseEntity<JsonNode>> r,int... expected) { assertThat(r.stream().mapToInt(x->x.getStatusCode().value()).sorted().toArray()).containsExactly(Arrays.stream(expected).sorted().toArray()); }
    private String key() { return UUID.randomUUID().toString(); }
    private String login(String username) { return ok(post("/auth/login",Map.of("username",username,"password","S1Demo!2026"),null,null),200).path("token").asText(); }
    private ResponseEntity<JsonNode> post(String path,Object body,String token,String key) { return request(HttpMethod.POST,path,body,token,key); }
    private ResponseEntity<JsonNode> get(String path,String token) { return request(HttpMethod.GET,path,null,token,null); }
    private ResponseEntity<JsonNode> request(HttpMethod method,String path,Object body,String token,String key) {
        var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.setBearerAuth(token);
        if(key!=null) headers.set("Idempotency-Key",key);
        // URI 已由用例显式编码，避免 RestTemplate 把百分号二次编码。
        return http.exchange(java.net.URI.create(http.getRootUri()+"/api/v1"+path),method,new HttpEntity<>(body,headers),JsonNode.class);
    }
}
