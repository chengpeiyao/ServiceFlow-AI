package com.serviceflow;

import cn.dev33.satoken.stp.StpUtil;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S2IntegrationTest extends MySqlTestSupport {
    @TestConfiguration
    static class TestEnvironment {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC); }
        @Bean TaskLockProbe taskLockProbe() { return new TaskLockProbe(); }
    }

    // 只在测试中同步真实 SQL 的入口或持锁位置，不替换 Mapper，也不伪造数据库返回值。
    @Intercepts(@Signature(type=Executor.class, method="query",
            args={MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class TaskLockProbe implements Interceptor {
        final AtomicReference<Gate> active = new AtomicReference<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Gate gate = active.get();
            if (gate == null || !((MappedStatement)invocation.getArgs()[0]).getId().equals("com.serviceflow.ticket.TicketMapper.lock"))
                return invocation.proceed();
            var connection = ((Executor)invocation.getTarget()).getTransaction().getConnection();
            try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT CONNECTION_ID()")) {
                row.next(); gate.connections.add(row.getLong(1));
            }
            long actor = StpUtil.getLoginIdAsLong();
            if (gate.holder == 0) {
                gate.arrived.countDown();
                if (!gate.arrived.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("双连接未到达工单锁入口");
                return invocation.proceed();
            }
            if (actor != gate.holder) gate.waiter.countDown();
            Object result = invocation.proceed();
            if (actor == gate.holder) {
                gate.held.countDown();
                if (!gate.release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("持锁测试未释放");
            }
            return result;
        }
        Gate use(long holder) { var gate = new Gate(holder); active.set(gate); return gate; }
        void clear() { Gate gate = active.getAndSet(null); if (gate != null) gate.release.countDown(); }
    }
    static class Gate {
        final long holder;
        final Set<Long> connections = ConcurrentHashMap.newKeySet();
        final CountDownLatch arrived = new CountDownLatch(2), held = new CountDownLatch(1),
                waiter = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate(long holder) { this.holder = holder; }
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskLockProbe probe;
    String customer, support, manager, engineer, backup;
    record Ref(long ticketId, long taskId, int ticketVersion, int taskVersion) {}

    @BeforeEach void reset() {
        clean();
        customer=login("customer_a"); support=login("support"); manager=login("manager");
        engineer=login("engineer"); backup=login("engineer_backup");
    }
    @AfterEach void clean() {
        probe.clear();
        jdbc.execute("DROP TRIGGER IF EXISTS s2_reject_audit");
        for (String table : List.of("ticket_triage_record","ai_triage_proposal","ai_triage_run",
                "ticket_activity","task_assignment_history","service_task","ticket_sla_event",
                "ticket_sla_instance","business_audit_log","service_ticket","idempotency_request"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE app_user SET account_status='ACTIVE'");
        jdbc.update("INSERT IGNORE INTO team_manager_scope(team_id,manager_user_id) VALUES(1,4)");
        jdbc.update("INSERT IGNORE INTO team_member(team_id,user_id) VALUES(1,8)");
        jdbc.update("UPDATE sla_policy_binding SET active_policy_version_id=1 WHERE service_type='DATABASE_SUPPORT' AND priority='P1'");
    }

    @Test @DisplayName("S2 创建、派工、接单、处理、完成；结果和双版本一致，SLA 不误停止")
    void completeLifecycle() {
        Ref r = created();
        var slaBefore = jdbc.queryForMap("SELECT * FROM ticket_sla_instance WHERE ticket_id=?",r.ticketId());
        r = assigned(r);
        r = progress(r,"accept",engineer);
        assertState(r,"ASSIGNED","ACCEPTED",5L);
        r = progress(r,"start",engineer);
        var command = with(versions(r),"result","已核对连接池日志，保留分析结论");
        var response = post(task(r,"complete"),command,engineer,"complete");
        Ref done = ref(response,200);
        assertState(done,"IN_PROGRESS","COMPLETED",5L);
        assertThat(done.ticketVersion()).isEqualTo(6);
        assertThat(done.taskVersion()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM service_task WHERE id=?",Boolean.class,done.taskId())).isTrue();
        assertThat(count("task_assignment_history")).isEqualTo(1);
        assertThat(count("ticket_activity")).isEqualTo(5);
        assertThat(count("business_audit_log")).isEqualTo(7);
        assertThat(jdbc.queryForMap("SELECT * FROM ticket_sla_instance WHERE ticket_id=?",done.ticketId())).isEqualTo(slaBefore);
        assertThat(jdbc.queryForObject("SELECT original_description FROM service_ticket WHERE id=?",String.class,done.ticketId())).isEqualTo("数据库连接超时");
        assertThat(get("/tickets/"+done.ticketId(),customer).getBody().path("activities").size()).isZero();
        assertThat(get("/tasks/"+done.taskId(),engineer).getBody().path("activities").toString()).contains("已核对连接池日志");
        assertThat(post(task(done,"complete"),command,engineer,"complete").getBody()).isEqualTo(response.getBody());
        assertThat(count("ticket_activity")).isEqualTo(5);
    }

    @ParameterizedTest @ValueSource(strings={"ASSIGNED","ACCEPTED","PROCESSING"})
    @DisplayName("S2 三种活动状态均可转派，新人重接单，旧人失权且历史保留")
    void reassignFromActiveStates(String state) {
        Ref r = stage(state);
        String ticketState = state.equals("PROCESSING") ? "IN_PROGRESS" : "ASSIGNED";
        long beforeActivities=count("ticket_activity");
        var command=transfer(r,5,8);
        var response=post(task(r,"reassignments"),command,manager,"transfer");
        Ref moved=ref(response,200);
        assertState(moved,ticketState,"ASSIGNED",8L);
        assertThat(count("ticket_activity")).isEqualTo(beforeActivities+1);
        var history=jdbc.queryForList("SELECT previous_assignee_id,new_assignee_id FROM task_assignment_history ORDER BY assigned_at,id");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("previous_assignee_id")).isNull();
        assertThat(((Number)history.get(1).get("previous_assignee_id")).longValue()).isEqualTo(5);
        assertThat(((Number)history.get(1).get("new_assignee_id")).longValue()).isEqualTo(8);
        assertCode(get("/tasks/"+moved.taskId(),engineer),404,"RESOURCE_FORBIDDEN");
        assertCode(get("/tickets/"+moved.ticketId(),engineer),404,"RESOURCE_FORBIDDEN");
        assertCode(post(task(moved,"start"),versions(moved),engineer,"old-start"),404,"RESOURCE_FORBIDDEN");
        assertCode(post(task(moved,"complete"),with(versions(moved),"result","旧人完成"),engineer,"old-complete"),404,"RESOURCE_FORBIDDEN");
        assertCode(post("/tickets/"+moved.ticketId()+"/messages",Map.of("expectedTicketVersion",moved.ticketVersion(),"content","旧人写消息"),engineer,"old-message"),404,"RESOURCE_FORBIDDEN");
        assertCode(post(task(moved,"start"),versions(moved),backup,"new-skip"),422,"INVALID_STATE_TRANSITION");
        if (!state.equals("ASSIGNED")) {
            assertCode(post(task(moved,"accept"),Map.of("expectedTicketVersion",3,"expectedTaskVersion",1),engineer,"accept"),404,"RESOURCE_FORBIDDEN");
        }
        assertThat(post(task(moved,"reassignments"),command,manager,"transfer").getBody()).isEqualTo(response.getBody());
        assertThat(count("task_assignment_history")).isEqualTo(2);
        Ref resumed=progress(progress(moved,"accept",backup),"start",backup);
        assertState(resumed,"IN_PROGRESS","PROCESSING",8L);
    }

    @Test @DisplayName("S2 主管范围与角色约束覆盖全部任务写入口")
    void roleAndTeamBoundaries() {
        Ref r=created();
        for(String token:List.of(customer,support,engineer,login("ops"))) {
            assertCode(post(task(r,"assignments"),assignment(r,5),token,key()),403,"RESOURCE_FORBIDDEN");
            assertCode(post("/tickets/"+r.ticketId()+"/tasks",Map.of("expectedTicketVersion",r.ticketVersion(),"title","越权任务"),token,key()),403,"RESOURCE_FORBIDDEN");
        }
        assertCode(post(task(r,"assignments"),assignment(r,5),login("manager_other"),key()),404,"RESOURCE_FORBIDDEN");
        assertCode(post(task(r,"accept"),versions(r),manager,key()),403,"RESOURCE_FORBIDDEN");
        assertThat(count("task_assignment_history")).isZero();
        assertState(r,"TRIAGED","CREATED",null);
    }

    @Test @DisplayName("S2 新负责人必须是有效工程师且属于工单团队")
    void engineerEligibility() {
        Ref r=created();
        for(long id:List.of(3L,9L,999999L))
            assertCode(post(task(r,"assignments"),assignment(r,id),manager,key()),422,"ASSIGNEE_NOT_ELIGIBLE");
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=8");
        assertCode(post(task(r,"assignments"),assignment(r,8),manager,key()),422,"ASSIGNEE_NOT_ELIGIBLE");
        jdbc.update("UPDATE app_user SET account_status='ACTIVE' WHERE id=8");
        jdbc.update("DELETE FROM team_member WHERE team_id=1 AND user_id=8");
        assertCode(post(task(r,"assignments"),assignment(r,8),manager,key()),422,"ASSIGNEE_NOT_ELIGIBLE");
        assertThat(count("task_assignment_history")).isZero();
        assertState(r,"TRIAGED","CREATED",null);
    }

    @Test @DisplayName("S2 双版本、旧负责人及同人转派分别校验")
    void versionsAndSameAssignee() {
        Ref r=assigned(created());
        var staleTicket=transfer(r,5,8); staleTicket.put("expectedTicketVersion",r.ticketVersion()-1);
        assertCode(post(task(r,"reassignments"),staleTicket,manager,key()),409,"VERSION_CONFLICT");
        var staleTask=transfer(r,5,8); staleTask.put("expectedTaskVersion",r.taskVersion()-1);
        assertCode(post(task(r,"reassignments"),staleTask,manager,key()),409,"TASK_VERSION_CONFLICT");
        assertCode(post(task(r,"reassignments"),transfer(r,8,5),manager,key()),409,"ASSIGNEE_CONFLICT");
        assertCode(post(task(r,"reassignments"),transfer(r,5,5),manager,key()),422,"SAME_ASSIGNEE");
        assertThat(count("task_assignment_history")).isEqualTo(1);
        assertState(r,"ASSIGNED","ASSIGNED",5L);
    }

    @Test @DisplayName("S2 任务创建只开放 TRIAGED，活动任务唯一")
    void createStateAndUniqueTask() {
        long id=submitted();
        assertCode(post("/tickets/"+id+"/tasks",Map.of("expectedTicketVersion",0,"title","尚未受理"),manager,key()),422,"INVALID_STATE_TRANSITION");
        Ref r=created();
        assertCode(post("/tickets/"+r.ticketId()+"/tasks",Map.of("expectedTicketVersion",r.ticketVersion(),"title","重复任务"),manager,key()),409,"ACTIVE_TASK_EXISTS");
        for(String state:List.of("ASSIGNED","IN_PROGRESS","PENDING_ACCEPTANCE","CLOSED","CANCELLED")) {
            jdbc.update("UPDATE service_ticket SET status=? WHERE id=?",state,r.ticketId());
            assertCode(post("/tickets/"+r.ticketId()+"/tasks",Map.of("expectedTicketVersion",r.ticketVersion(),"title","不合法任务"),manager,key()),422,"INVALID_STATE_TRANSITION");
        }
        assertThat(count("service_task")).isEqualTo(1);
    }

    @Test @DisplayName("S2 只允许未派任务取消并新建替代")
    void cancelAndReplace() {
        Ref r=created();
        r=ref(post(task(r,"cancel"),with(versions(r),"reason","任务范围填写错误"),manager,"cancel"),200);
        assertState(r,"TRIAGED","CANCELLED",null);
        assertThat(jdbc.queryForObject("SELECT active_blocking_ticket_id IS NULL FROM service_task WHERE id=?",Boolean.class,r.taskId())).isTrue();
        assertCode(post(task(r,"assignments"),assignment(r,5),manager,key()),422,"INVALID_STATE_TRANSITION");
        Ref replacement=ref(post("/tickets/"+r.ticketId()+"/tasks",Map.of("expectedTicketVersion",r.ticketVersion(),"title","替代任务"),manager,"replacement"),201);
        replacement=assigned(replacement);
        assertCode(post(task(replacement,"cancel"),with(versions(replacement),"reason","不能取消已派任务"),manager,key()),422,"INVALID_STATE_TRANSITION");
        assertThat(count("service_task")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_task WHERE active_blocking_ticket_id IS NOT NULL",Integer.class)).isEqualTo(1);
    }

    @Test @DisplayName("S2 不允许跳过接单，完成任务不可再转派或开始")
    void stateMachineRejectsSkippingAndTerminalWrites() {
        Ref r=created();
        assertCode(post(task(r,"reassignments"),transfer(r,5,8),manager,key()),422,"INVALID_STATE_TRANSITION");
        r=assigned(r);
        assertCode(post(task(r,"start"),versions(r),engineer,key()),422,"INVALID_STATE_TRANSITION");
        assertCode(post(task(r,"complete"),with(versions(r),"result","提前完成"),engineer,key()),422,"INVALID_STATE_TRANSITION");
        r=progress(progress(r,"accept",engineer),"start",engineer);
        r=ref(post(task(r,"complete"),with(versions(r),"result","完成处理"),engineer,key()),200);
        assertCode(post(task(r,"reassignments"),transfer(r,5,8),manager,key()),422,"INVALID_STATE_TRANSITION");
        assertCode(post(task(r,"assignments"),assignment(r,8),manager,key()),422,"INVALID_STATE_TRANSITION");
        assertCode(post(task(r,"start"),versions(r),engineer,key()),422,"INVALID_STATE_TRANSITION");
        assertCode(post(task(r,"complete"),with(versions(r),"result","重复完成"),engineer,key()),422,"INVALID_STATE_TRANSITION");
        assertThat(count("task_assignment_history")).isEqualTo(1);
        assertState(r,"IN_PROGRESS","COMPLETED",5L);
    }

    @Test @DisplayName("S2 任务详情与内部记录隔离")
    void internalTaskReadAuthorization() {
        Ref r=assigned(created());
        for(String token:List.of(customer,login("customer_b"),backup,login("manager_other")))
            assertCode(get("/tasks/"+r.taskId(),token),404,"RESOURCE_FORBIDDEN");
        for(String token:List.of(manager,engineer,support,login("ops")))
            assertThat(get("/tasks/"+r.taskId(),token).getStatusCode().value()).isEqualTo(200);
        assertCode(get("/tasks/999999",manager),404,"RESOURCE_FORBIDDEN");
    }

    @Test @DisplayName("S2 请求校验禁止业务字段注入、空结果和缺失幂等键")
    void requestValidation() {
        Ref r=created();
        assertCode(post(task(r,"assignments"),assignment(r,5),manager,null),400,"IDEMPOTENCY_KEY_REQUIRED");
        var injected=assignment(r,5); injected.put("ownerTeamId",2);
        assertCode(post(task(r,"assignments"),injected,manager,key()),400,"REQUEST_INVALID");
        assertCode(post(task(r,"assignments"),with(assignment(r,5),"reason"," "),manager,key()),400,"REQUEST_INVALID");
        assertCode(post(task(r,"complete"),with(versions(r),"result"," "),engineer,key()),400,"REQUEST_INVALID");
        assertCode(post(task(r,"accept"),Map.of("expectedTicketVersion",r.ticketVersion()),engineer,key()),400,"REQUEST_INVALID");
        assertThat(count("task_assignment_history")).isZero();
    }

    @Test @DisplayName("S2 幂等重放不重复历史；内容或资源不同则冲突")
    void idempotencyAndResourceDigest() {
        Ref r=created(); var command=assignment(r,5);
        var first=post(task(r,"assignments"),command,manager,"assign");
        ref(first,200);
        assertThat(post(task(r,"assignments"),command,manager,"assign").getBody()).isEqualTo(first.getBody());
        assertCode(post(task(r,"assignments"),with(command,"reason","不同正文"),manager,"assign"),409,"IDEMPOTENCY_KEY_CONFLICT");
        Ref other=created();
        assertCode(post(task(other,"assignments"),command,manager,"assign"),409,"IDEMPOTENCY_KEY_CONFLICT");
        assertThat(count("task_assignment_history")).isEqualTo(1);
        var createBody=Map.of("expectedTicketVersion",1,"title","诊断任务");
        assertThat(post("/tickets/"+r.ticketId()+"/tasks",createBody,manager,"create-task-"+r.ticketId()).getBody().path("taskId").asLong()).isEqualTo(r.taskId());
        assertThat(count("service_task")).isEqualTo(2);
    }

    @Test @DisplayName("S2 幂等重放不能绕过已撤销主管权限")
    void replayRechecksManagerScope() {
        Ref r=created(); var command=assignment(r,5);
        ref(post(task(r,"assignments"),command,manager,"assign"),200);
        jdbc.update("DELETE FROM team_manager_scope WHERE manager_user_id=4 AND team_id=1");
        assertCode(post(task(r,"assignments"),command,manager,"assign"),404,"RESOURCE_FORBIDDEN");
        assertThat(count("task_assignment_history")).isEqualTo(1);
    }

    @Test @DisplayName("S2 审计失败时转派历史、双版本、活动及幂等记录原子回滚")
    void reassignRollsBackAllSideEffects() {
        Ref r=stage("PROCESSING");
        var before=new LinkedHashMap<String,Long>();
        for(String table:List.of("task_assignment_history","ticket_activity","business_audit_log","idempotency_request")) before.put(table,count(table));
        jdbc.execute("CREATE TRIGGER s2_reject_audit BEFORE INSERT ON business_audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='S2 audit rollback probe'");
        try {
            assertCode(post(task(r,"reassignments"),transfer(r,5,8),manager,"rollback-transfer"),500,"INTERNAL_ERROR");
            assertState(r,"IN_PROGRESS","PROCESSING",5L);
            before.forEach((table,number)->assertThat(count(table)).as(table).isEqualTo(number));
        } finally { jdbc.execute("DROP TRIGGER s2_reject_audit"); }
        Ref moved=ref(post(task(r,"reassignments"),transfer(r,5,8),manager,"rollback-transfer"),200);
        assertState(moved,"IN_PROGRESS","ASSIGNED",8L);
        assertThat(count("task_assignment_history")).isEqualTo(2);
    }

    @Test @DisplayName("S2 数据库唯一约束独立阻止第二个活动阻塞任务")
    void databaseUniqueConstraint() {
        Ref r=created();
        assertThatThrownBy(()->jdbc.update("INSERT INTO service_task(id,ticket_id,title,is_blocking,status,version,created_by,created_at) VALUES(900001,?,'直接 SQL 竞争',1,'CREATED',0,4,NOW(3))",r.ticketId()))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(count("service_task")).isEqualTo(1);
    }

    @Test @DisplayName("TX-01 两个真实 MySQL 连接同时建任务，仅一个成功")
    void concurrentCreate() throws Exception {
        long ticketId=triaged(); String manager2=login("manager_backup");
        Object command=Map.of("expectedTicketVersion",1,"title","竞争任务");
        var results=race(()->post("/tickets/"+ticketId+"/tasks",command,manager,"race-create-a"),
                ()->post("/tickets/"+ticketId+"/tasks",command,manager2,"race-create-b"));
        assertStatuses(results,201,409);
        assertThat(count("service_task")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM service_ticket WHERE id=?",Integer.class,ticketId)).isEqualTo(2);
    }

    @Test @DisplayName("TX-03 双连接首次派工，负责人和唯一历史一致")
    void concurrentAssign() throws Exception {
        Ref r=created(); String manager2=login("manager_backup");
        var results=race(()->post(task(r,"assignments"),assignment(r,5),manager,"race-assign-a"),
                ()->post(task(r,"assignments"),assignment(r,8),manager2,"race-assign-b"));
        assertStatuses(results,200,409);
        assertThat(count("task_assignment_history")).isEqualTo(1);
        long assignee=jdbc.queryForObject("SELECT current_assignee_id FROM service_task WHERE id=?",Long.class,r.taskId());
        assertThat(jdbc.queryForObject("SELECT new_assignee_id FROM task_assignment_history WHERE task_id=?",Long.class,r.taskId())).isEqualTo(assignee);
        assertState(new Ref(r.ticketId(),r.taskId(),3,1),"ASSIGNED","ASSIGNED",assignee);
    }

    @Test @DisplayName("TX-07 双连接同键派工均返回原结果，仅一次副作用")
    void concurrentAssignmentReplay() throws Exception {
        Ref r=created(); var command=assignment(r,5);
        var results=race(()->post(task(r,"assignments"),command,manager,"same-assignment"),
                ()->post(task(r,"assignments"),command,manager,"same-assignment"));
        assertStatuses(results,200,200);
        assertThat(results.get(0).getBody()).isEqualTo(results.get(1).getBody());
        assertThat(count("task_assignment_history")).isEqualTo(1);
        assertThat(count("ticket_activity")).isEqualTo(2);
    }

    @Test @DisplayName("TX-03 双连接转派，只有一个旧版本请求成功")
    void concurrentReassign() throws Exception {
        Ref r=stage("PROCESSING"); String manager2=login("manager_backup");
        var results=race(()->post(task(r,"reassignments"),transfer(r,5,8),manager,"race-transfer-a"),
                ()->post(task(r,"reassignments"),transfer(r,5,8),manager2,"race-transfer-b"));
        assertStatuses(results,200,409);
        assertThat(count("task_assignment_history")).isEqualTo(2);
        assertState(new Ref(r.ticketId(),r.taskId(),r.ticketVersion()+1,r.taskVersion()+1),"IN_PROGRESS","ASSIGNED",8L);
    }

    @ParameterizedTest @ValueSource(strings={"start","complete"})
    @DisplayName("TX-08 转派先获得工单锁，旧负责人开始或完成均被拒绝")
    void transferWinsAgainstOldOwner(String operation) throws Exception {
        Ref r=stage(operation.equals("start")?"ACCEPTED":"PROCESSING");
        Object oldCommand=operation.equals("start")?versions(r):with(versions(r),"result","不应落库的旧负责人结果");
        var results=orderedRace(4,()->post(task(r,"reassignments"),transfer(r,5,8),manager,"first-transfer"),
                ()->post(task(r,operation),oldCommand,engineer,"stale-owner"));
        assertThat(results.get(0).getStatusCode().value()).isEqualTo(200);
        assertCode(results.get(1),404,"RESOURCE_FORBIDDEN");
        Ref moved=ref(results.get(0),200);
        assertState(moved,operation.equals("start")?"ASSIGNED":"IN_PROGRESS","ASSIGNED",8L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_activity WHERE body='不应落库的旧负责人结果'",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"start","complete"})
    @DisplayName("TX-08 工程师先获得锁合法提交，后到的旧版本转派冲突")
    void oldOwnerWinsBeforeTransfer(String operation) throws Exception {
        Ref r=stage(operation.equals("start")?"ACCEPTED":"PROCESSING");
        Object oldCommand=operation.equals("start")?versions(r):with(versions(r),"result","转派前合法完成");
        var results=orderedRace(5,()->post(task(r,operation),oldCommand,engineer,"first-owner"),
                ()->post(task(r,"reassignments"),transfer(r,5,8),manager,"stale-transfer"));
        Ref progressed=ref(results.get(0),200);
        assertCode(results.get(1),409,"VERSION_CONFLICT");
        assertState(progressed,"IN_PROGRESS",operation.equals("start")?"PROCESSING":"COMPLETED",5L);
        assertThat(count("task_assignment_history")).isEqualTo(1);
        if(operation.equals("complete"))
            assertCode(post(task(progressed,"reassignments"),transfer(progressed,5,8),manager,"terminal-transfer"),422,"INVALID_STATE_TRANSITION");
        else
            assertState(ref(post(task(progressed,"reassignments"),transfer(progressed,5,8),manager,"refreshed-transfer"),200),"IN_PROGRESS","ASSIGNED",8L);
    }

    @Test @DisplayName("S2 任务变更与 S1 客户消息共享工单锁及版本")
    void taskAndCustomerMessageCompete() throws Exception {
        Ref r=stage("ACCEPTED");
        var results=race(()->post(task(r,"start"),versions(r),engineer,"start"),
                ()->post("/tickets/"+r.ticketId()+"/messages",Map.of("expectedTicketVersion",r.ticketVersion(),"content","客户补充"),customer,"supplement"));
        assertThat(results.stream().filter(x->x.getStatusCode().value()==409).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM service_ticket WHERE id=?",Integer.class,r.ticketId())).isEqualTo(r.ticketVersion()+1);
        assertThat(jdbc.queryForObject("SELECT version FROM service_task WHERE id=?",Integer.class,r.taskId()))
                .isEqualTo(r.taskVersion()+(results.get(0).getStatusCode().value()==200?1:0));
    }

    private List<ResponseEntity<JsonNode>> race(Callable<ResponseEntity<JsonNode>> first, Callable<ResponseEntity<JsonNode>> second) throws Exception {
        Gate gate=probe.use(0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(first); var b=pool.submit(second);
            var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(gate.connections).hasSize(2);
            return results;
        } finally { probe.clear(); }
    }
    private List<ResponseEntity<JsonNode>> orderedRace(long holder, Callable<ResponseEntity<JsonNode>> first, Callable<ResponseEntity<JsonNode>> second) throws Exception {
        Gate gate=probe.use(holder);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(first);
            assertThat(gate.held.await(10,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(second);
            try { assertThat(gate.waiter.await(10,TimeUnit.SECONDS)).isTrue(); }
            finally { gate.release.countDown(); }
            var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(gate.connections).hasSize(2);
            return results;
        } finally { probe.clear(); }
    }
    private Ref stage(String state) {
        Ref r=assigned(created());
        if(!state.equals("ASSIGNED")) r=progress(r,"accept",engineer);
        if(state.equals("PROCESSING")) r=progress(r,"start",engineer);
        return r;
    }
    private long submitted() {
        var response=post("/tickets",Map.of("customerId",1,"contractId",1,"title","数据库连接故障","description","数据库连接超时",
                "serviceType","DATABASE_SUPPORT","impact","HIGH","urgency","HIGH"),customer,key());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().path("ticketId").asLong();
    }
    private long triaged() {
        long id=submitted();
        var response=post("/tickets/"+id+"/triage",Map.of("expectedTicketVersion",0,"confirmedCategory","DATABASE_CONNECTION",
                "confirmedFacts",List.of(),"confirmedFollowups",List.of(),"confirmedDiagnostics",List.of()),support,key());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return id;
    }
    private Ref created() {
        long id=triaged();
        return ref(post("/tickets/"+id+"/tasks",Map.of("expectedTicketVersion",1,"title","诊断任务"),manager,"create-task-"+id),201);
    }
    private Ref assigned(Ref r) { return ref(post(task(r,"assignments"),assignment(r,5),manager,key()),200); }
    private Ref progress(Ref r,String operation,String actor) { return ref(post(task(r,operation),versions(r),actor,operation),200); }
    private Map<String,Object> versions(Ref r) { return new LinkedHashMap<>(Map.of("expectedTicketVersion",r.ticketVersion(),"expectedTaskVersion",r.taskVersion())); }
    private Map<String,Object> assignment(Ref r,long engineerId) { var c=versions(r); c.put("assigneeId",engineerId); c.put("reason","按团队技能派工"); return c; }
    private Map<String,Object> transfer(Ref r,long previous,long next) { var c=assignment(r,next); c.put("expectedAssigneeId",previous); c.put("reason","值班交接"); return c; }
    private Map<String,Object> with(Map<String,Object> source,String key,Object value) { var result=new LinkedHashMap<>(source); result.put(key,value); return result; }
    private String task(Ref r,String operation) { return "/tasks/"+r.taskId()+"/"+operation; }
    private String key() { return UUID.randomUUID().toString(); }
    private Ref ref(ResponseEntity<JsonNode> response,int status) {
        assertThat(response.getStatusCode().value()).as(response.getBody().toString()).isEqualTo(status);
        var body=response.getBody();
        return new Ref(body.path("ticketId").asLong(),body.path("taskId").asLong(),body.path("ticketVersion").asInt(),body.path("taskVersion").asInt());
    }
    private void assertState(Ref r,String ticketStatus,String taskStatus,Long owner) {
        var ticket=jdbc.queryForMap("SELECT status,version FROM service_ticket WHERE id=?",r.ticketId());
        var task=jdbc.queryForMap("SELECT status,version,current_assignee_id FROM service_task WHERE id=?",r.taskId());
        assertThat(ticket.get("status")).isEqualTo(ticketStatus); assertThat(((Number)ticket.get("version")).intValue()).isEqualTo(r.ticketVersion());
        assertThat(task.get("status")).isEqualTo(taskStatus); assertThat(((Number)task.get("version")).intValue()).isEqualTo(r.taskVersion());
        assertThat(task.get("current_assignee_id")==null?null:((Number)task.get("current_assignee_id")).longValue()).isEqualTo(owner);
    }
    private void assertStatuses(List<ResponseEntity<JsonNode>> responses,int... statuses) {
        assertThat(responses.stream().mapToInt(x->x.getStatusCode().value()).sorted().toArray()).containsExactly(Arrays.stream(statuses).sorted().toArray());
    }
    private void assertCode(ResponseEntity<JsonNode> response,int status,String code) {
        assertThat(response.getStatusCode().value()).as(response.getBody().toString()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private String login(String username) {
        var response=post("/auth/login",Map.of("username",username,"password","S1Demo!2026"),null,null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("token").asText();
    }
    private ResponseEntity<JsonNode> get(String path,String token) { return request(HttpMethod.GET,path,null,token,null); }
    private ResponseEntity<JsonNode> post(String path,Object body,String token,String key) { return request(HttpMethod.POST,path,body,token,key); }
    private ResponseEntity<JsonNode> request(HttpMethod method,String path,Object body,String token,String key) {
        var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.setBearerAuth(token);
        if(key!=null) headers.set("Idempotency-Key",key);
        return http.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
}
