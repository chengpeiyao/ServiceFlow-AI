package com.serviceflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S1IntegrationTest extends MySqlTestSupport {
    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC); }
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    String customerA;
    String customerB;
    String support;

    @BeforeEach
    void reset() {
        for (String table : List.of("ticket_triage_record", "ai_triage_proposal", "ai_triage_run", "ticket_activity",
                "ticket_acceptance", "task_assignment_history", "service_task", "ticket_resolution",
                "ticket_sla_event", "ticket_sla_instance", "business_audit_log", "service_ticket", "idempotency_request")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE app_user SET account_status='ACTIVE'");
        jdbc.update("UPDATE sla_policy_binding SET active_policy_version_id=1 WHERE service_type='DATABASE_SUPPORT' AND priority='P1'");
        customerA = login("customer_a");
        customerB = login("customer_b");
        support = login("support");
    }

    @Test @DisplayName("S1-01 合法创建冻结团队、优先级及 SLA 并保留原文")
    void s1_01_createAndFreeze() {
        var response = post("/tickets", createCommand(1,1), customerA, "create-1");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        long id = response.getBody().path("ticketId").asLong();
        var detail = get("/tickets/" + id, customerA).getBody();
        assertThat(detail.path("ticket").path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(detail.path("ticket").path("priority").asText()).isEqualTo("P1");
        assertThat(detail.path("ticket").path("ownerTeamId").asLong()).isEqualTo(1);
        assertThat(detail.path("ticket").path("originalDescription").asText()).isEqualTo("升级后提示数据库连接超时");
        assertThat(detail.path("sla").path("policyVersionId").asLong()).isEqualTo(1);
        assertThat(detail.path("sla").path("responseDueAt").asText()).isEqualTo("2026-10-19T09:00:00Z");
        assertThat(count("business_audit_log")).isEqualTo(1);
        assertThat(count("ticket_sla_event")).isEqualTo(1);
    }

    @Test @DisplayName("S1-02 跨客户合同不产生工单、SLA 或成功审计")
    void s1_02_crossCustomerContract() {
        var response = post("/tickets", createCommand(1,2), customerA, "bad-contract");
        assertCode(response, 422, "CONTRACT_NOT_ELIGIBLE");
        assertNoCreatedData();
    }

    @Test @DisplayName("S1-03 过期、停用和未覆盖合同均拒绝")
    void s1_03_invalidCoverage() {
        for (long contract : List.of(3L,4L)) {
            assertCode(post("/tickets", createCommand(1,contract), customerA, "invalid-" + contract),422,"CONTRACT_NOT_ELIGIBLE");
        }
        var command = createCommand(1,1);
        command.put("serviceType", "APPLICATION_SUPPORT");
        assertCode(post("/tickets", command, customerA, "not-covered"),422,"CONTRACT_NOT_ELIGIBLE");
        assertNoCreatedData();
    }

    @Test @DisplayName("S1-04 切换策略只影响新工单")
    void s1_04_policySnapshot() {
        long first = create();
        jdbc.update("UPDATE sla_policy_binding SET active_policy_version_id=7 WHERE service_type='DATABASE_SUPPORT' AND priority='P1'");
        long second = create();
        assertThat(get("/tickets/" + first + "/sla",customerA).getBody().path("policyVersionId").asLong()).isEqualTo(1);
        assertThat(get("/tickets/" + second + "/sla",customerA).getBody().path("policyVersionId").asLong()).isEqualTo(7);
    }

    @Test @DisplayName("S1-05 客户之间不能读取工单及 SLA")
    void s1_05_dataIsolation() {
        long id = create();
        var denied = get("/tickets/" + id, customerB);
        assertCode(denied,404,"RESOURCE_FORBIDDEN");
        assertThat(denied.getBody().toString()).doesNotContain("数据库连接超时", "originalDescription");
        assertCode(get("/tickets/" + id + "/sla",customerB),404,"RESOURCE_FORBIDDEN");
    }

    @Test @DisplayName("S1-06 产品信息可不提供，持久化为 NULL")
    void s1_06_unknownProduct() {
        long id = create();
        var product = jdbc.queryForMap("SELECT reported_product_code,reported_product_version FROM service_ticket WHERE id=?",id);
        assertThat(product.get("reported_product_code")).isNull();
        assertThat(product.get("reported_product_version")).isNull();
    }

    @Test @DisplayName("S1-07 客户补充产品时保留原文、递增版本并审计")
    void s1_07_customerSupplement() {
        long id = create();
        var command = message(0,"产品 COMS，版本 11.2");
        command.put("reportedProductCode","COMS");
        command.put("reportedProductVersion","11.2");
        var response = post("/tickets/" + id + "/messages",command,customerA,"supplement");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var detail = get("/tickets/" + id,customerA).getBody();
        assertThat(detail.path("ticket").path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("ticket").path("reportedProductCode").asText()).isEqualTo("COMS");
        assertThat(detail.path("ticket").path("originalDescription").asText()).isEqualTo("升级后提示数据库连接超时");
        assertThat(detail.path("sla").path("firstRespondedAt").isNull()).isTrue();
        assertThat(count("ticket_activity")).isEqualTo(1);
        assertThat(count("business_audit_log")).isEqualTo(2);
    }

    @Test @DisplayName("S1-08 人工受理只修改允许字段且不停止首响")
    void s1_08_manualTriage() {
        long id = create();
        var response = post("/tickets/" + id + "/triage",triage(0),support,"triage");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("source").asText()).isEqualTo("MANUAL");
        var detail = get("/tickets/" + id,customerA).getBody();
        assertThat(detail.path("ticket").path("status").asText()).isEqualTo("TRIAGED");
        assertThat(detail.path("ticket").path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("ticket").path("serviceType").asText()).isEqualTo("DATABASE_SUPPORT");
        assertThat(detail.path("ticket").path("contractId").asLong()).isEqualTo(1);
        assertThat(detail.path("ticket").path("ownerTeamId").asLong()).isEqualTo(1);
        assertThat(detail.path("sla").path("policyVersionId").asLong()).isEqualTo(1);
        assertThat(detail.path("sla").path("firstRespondedAt").isNull()).isTrue();
        assertThat(count("ticket_triage_record")).isEqualTo(1);
    }

    @Test @DisplayName("S1-09 冻结字段和未知受理字段拒绝，状态不变")
    void s1_09_illegalFields() {
        long id = create();
        for (String field : List.of("serviceType","ownerTeamId","priority","slaPolicyVersionId","originalDescription","reportedProductCode")) {
            var command = triage(0);
            command.put(field,"invalid");
            assertCode(post("/tickets/" + id + "/triage",command,support,"bad-"+field),422,"TRIAGE_FIELD_NOT_EDITABLE");
        }
        assertThat(count("ticket_triage_record")).isZero();
        assertThat(get("/tickets/" + id,customerA).getBody().path("ticket").path("version").asInt()).isZero();
    }

    @Test @DisplayName("S1-10A 补充先提交，旧版本受理被拒绝")
    void s1_10a_supplementFirst() {
        long id = create();
        assertThat(post("/tickets/"+id+"/messages",message(0,"补充日志"),customerA,"message").getStatusCode().value()).isEqualTo(201);
        assertCode(post("/tickets/"+id+"/triage",triage(0),support,"stale"),409,"VERSION_CONFLICT");
        assertThat(count("ticket_activity")).isEqualTo(1);
        assertThat(count("ticket_triage_record")).isZero();
    }

    @Test @DisplayName("S1-10B 受理先提交，旧版本补充被明确拒绝")
    void s1_10b_triageFirst() {
        long id = create();
        assertThat(post("/tickets/"+id+"/triage",triage(0),support,"triage").getStatusCode().value()).isEqualTo(200);
        assertCode(post("/tickets/"+id+"/messages",message(0,"补充日志"),customerA,"stale"),409,"VERSION_CONFLICT");
        assertThat(count("ticket_activity")).isZero();
        assertThat(count("ticket_triage_record")).isEqualTo(1);
    }

    @Test @DisplayName("S1-10C 两条 HTTP 请求并发竞争，只允许一个旧版本写入成功")
    void s1_10c_realConcurrentRequests() throws Exception {
        long id = create();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var supplement = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return post("/tickets/"+id+"/messages",message(0,"并发补充"),customerA,"race-message"); });
            var triage = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return post("/tickets/"+id+"/triage",triage(0),support,"race-triage"); });
            var a = supplement.get(15,TimeUnit.SECONDS);
            var b = triage.get(15,TimeUnit.SECONDS);
            assertThat(List.of(a.getStatusCode().value(),b.getStatusCode().value()).stream().filter(status -> status==409).count()).isEqualTo(1);
            var detail = get("/tickets/"+id,customerA).getBody();
            assertThat(detail.path("ticket").path("version").asInt()).isEqualTo(1);
            if (a.getStatusCode().value()==201) {
                assertThat(count("ticket_activity")).isEqualTo(1);
                assertThat(count("ticket_triage_record")).isZero();
                assertThat(detail.path("ticket").path("status").asText()).isEqualTo("SUBMITTED");
            } else {
                assertThat(b.getStatusCode().value()).isEqualTo(200);
                assertThat(count("ticket_activity")).isZero();
                assertThat(count("ticket_triage_record")).isEqualTo(1);
            }
        }
    }

    @Test @DisplayName("登录失败、未登录和退出后访问均拒绝")
    void authentication() {
        assertCode(post("/auth/login",Map.of("username","customer_a","password","wrong"),null,null),401,"LOGIN_FAILED");
        assertCode(get("/auth/me",null),401,"UNAUTHENTICATED");
        assertThat(post("/auth/logout",null,customerA,null).getStatusCode().value()).isEqualTo(200);
        assertCode(get("/auth/me",customerA),401,"UNAUTHENTICATED");
    }

    @Test @DisplayName("账号停用后已有会话也失效")
    void disabledAccount() {
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=1");
        assertCode(get("/auth/me",customerA),401,"UNAUTHENTICATED");
    }

    @Test @DisplayName("客户不能冒充其他客户创建，也不能执行客服受理")
    void rolesAndCustomerScope() {
        assertCode(post("/tickets",createCommand(2,2),customerA,"impersonate"),404,"RESOURCE_FORBIDDEN");
        long id = create();
        assertCode(post("/tickets/"+id+"/triage",triage(0),customerA,"forbidden"),403,"RESOURCE_FORBIDDEN");
        assertThat(count("ticket_triage_record")).isZero();
    }

    @Test @DisplayName("主管团队范围限制读取，未分配工程师不能读取")
    void staffResourceScope() {
        long id = create();
        assertThat(get("/tickets/"+id,login("manager")).getStatusCode().value()).isEqualTo(200);
        assertCode(get("/tickets/"+id,login("manager_other")),404,"RESOURCE_FORBIDDEN");
        assertCode(get("/tickets/"+id,login("engineer")),404,"RESOURCE_FORBIDDEN");
    }

    @Test @DisplayName("公开人工回复停止首响，客户补充和内部备注不停止")
    void firstResponseVisibility() {
        long id = create();
        var note = message(0,"内部备注"); note.put("customerVisible",false);
        post("/tickets/"+id+"/messages",note,support,"internal");
        assertThat(get("/tickets/"+id+"/sla",customerA).getBody().path("firstRespondedAt").isNull()).isTrue();
        var publicReply = post("/tickets/"+id+"/messages",message(1,"已受理您的问题，正在检查连接日志"),support,"reply");
        assertThat(publicReply.getStatusCode().value()).isEqualTo(201);
        var detail = get("/tickets/"+id,customerA).getBody();
        assertThat(detail.path("activities").size()).isEqualTo(1);
        assertThat(detail.path("activities").get(0).path("body").asText()).doesNotContain("内部备注");
        assertThat(detail.path("sla").path("responseMet").asInt()).isEqualTo(1);
        assertThat(count("ticket_sla_event")).isEqualTo(2);
    }

    @Test @DisplayName("创建和受理重放原结果，不重复记录")
    void successfulReplay() {
        var first = post("/tickets",createCommand(1,1),customerA,"same-create");
        var second = post("/tickets",createCommand(1,1),customerA,"same-create");
        assertThat(first.getBody()).isEqualTo(second.getBody());
        long id = first.getBody().path("ticketId").asLong();
        var triaged = post("/tickets/"+id+"/triage",triage(0),support,"same-triage");
        var replay = post("/tickets/"+id+"/triage",triage(0),support,"same-triage");
        assertThat(triaged.getStatusCode().value()).isEqualTo(200);
        assertThat(triaged.getBody()).isEqualTo(replay.getBody());
        assertThat(count("service_ticket")).isEqualTo(1);
        assertThat(count("ticket_triage_record")).isEqualTo(1);
        assertThat(count("business_audit_log")).isEqualTo(2);
    }

    @Test @DisplayName("相同请求键不同正文或资源返回冲突")
    void replayConflict() {
        post("/tickets",createCommand(1,1),customerA,"same-key");
        var different = createCommand(1,1); different.put("title","不同请求");
        assertCode(post("/tickets",different,customerA,"same-key"),409,"IDEMPOTENCY_KEY_CONFLICT");
        long first = create(); long second = create();
        post("/tickets/"+first+"/messages",message(0,"补充"),customerA,"same-message");
        assertCode(post("/tickets/"+second+"/messages",message(0,"补充"),customerA,"same-message"),409,"IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test @DisplayName("相同请求键并发创建仅产生一张工单")
    void concurrentCreateReplay() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<ResponseEntity<JsonNode>> call = () -> { barrier.await(5,TimeUnit.SECONDS); return post("/tickets",createCommand(1,1),customerA,"concurrent-create"); };
            var a = pool.submit(call); var b = pool.submit(call);
            var first = a.get(15,TimeUnit.SECONDS); var second = b.get(15,TimeUnit.SECONDS);
            assertThat(first.getStatusCode().value()).isEqualTo(201);
            assertThat(second.getStatusCode().value()).isEqualTo(201);
            assertThat(first.getBody()).isEqualTo(second.getBody());
            assertThat(count("service_ticket")).isEqualTo(1);
            assertThat(count("ticket_sla_instance")).isEqualTo(1);
        }
    }

    @Test @DisplayName("审计写入失败时工单、SLA、事件和幂等结果一并回滚")
    void rollbackOnAuditFailure() {
        jdbc.execute("CREATE TRIGGER s1_reject_audit BEFORE INSERT ON business_audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='S1 audit failure probe'");
        try {
            assertCode(post("/tickets",createCommand(1,1),customerA,"rollback"),500,"INTERNAL_ERROR");
            assertNoCreatedData();
            assertThat(count("ticket_sla_event")).isZero();
        } finally { jdbc.execute("DROP TRIGGER s1_reject_audit"); }
    }

    @Test @DisplayName("未知产品拒绝，版本可以单独申报，切换产品不继承旧版本")
    void productValidation() {
        var invalid = createCommand(1,1); invalid.put("reportedProductCode","UNLISTED");
        assertCode(post("/tickets",invalid,customerA,"bad-product"),422,"PRODUCT_IDENTIFICATION_INVALID");
        var onlyVersion = createCommand(1,1); onlyVersion.put("reportedProductVersion","11.2");
        long versionOnlyId = post("/tickets",onlyVersion,customerA,"version-only").getBody().path("ticketId").asLong();
        var versionOnly = get("/tickets/"+versionOnlyId,customerA).getBody().path("ticket");
        assertThat(versionOnly.path("reportedProductCode").isNull()).isTrue();
        assertThat(versionOnly.path("reportedProductVersion").asText()).isEqualTo("11.2");
        var known = createCommand(1,1); known.put("reportedProductCode","COMS"); known.put("reportedProductVersion","11.2");
        long id = post("/tickets",known,customerA,"known").getBody().path("ticketId").asLong();
        var change = message(0,"改为 IOT 产品"); change.put("reportedProductCode","IOT");
        post("/tickets/"+id+"/messages",change,customerA,"switch-product");
        var detail = get("/tickets/"+id,customerA).getBody().path("ticket");
        assertThat(detail.path("reportedProductCode").asText()).isEqualTo("IOT");
        assertThat(detail.path("reportedProductVersion").isNull()).isTrue();
    }

    @Test @DisplayName("产品申报只允许客户在待受理阶段更新")
    void productUpdateBoundary() {
        long id = create();
        var change = message(0,"产品 COMS"); change.put("reportedProductCode","COMS");
        assertCode(post("/tickets/"+id+"/messages",change,support,"support-product"),422,"PRODUCT_IDENTIFICATION_INVALID");
        post("/tickets/"+id+"/triage",triage(0),support,"triage");
        change.put("expectedTicketVersion",1);
        assertCode(post("/tickets/"+id+"/messages",change,customerA,"late-product"),422,"PRODUCT_IDENTIFICATION_INVALID");
    }

    @Test @DisplayName("无来源的事实拒绝，受理事务不产生部分记录")
    void invalidFactSource() {
        long id = create();
        var command = triage(0);
        command.put("confirmedFacts",List.of(Map.of("field","symptom","value","确定数据库损坏","sourceQuote","并不存在的原文")));
        assertCode(post("/tickets/"+id+"/triage",command,support,"bad-source"),422,"FACT_SOURCE_INVALID");
        assertThat(count("ticket_triage_record")).isZero();
    }

    @Test @DisplayName("知识版本精确匹配，1.2 不应匹配 11.2")
    void exactKnowledgeVersion() {
        var known = createCommand(1,1); known.put("reportedProductCode","COMS"); known.put("reportedProductVersion","11.2");
        long id = post("/tickets",known,customerA,"known").getBody().path("ticketId").asLong();
        var command = triage(0);
        command.put("confirmedDiagnostics",List.of(Map.of("content","检查连接池","claimType","HYPOTHESIS","knowledgeRefs",List.of(202))));
        assertCode(post("/tickets/"+id+"/triage",command,support,"wrong-version"),422,"KNOWLEDGE_REFERENCE_INVALID");
        command.put("confirmedDiagnostics",List.of(Map.of("content","检查连接池","claimType","HYPOTHESIS","knowledgeRefs",List.of(203))));
        assertThat(post("/tickets/"+id+"/triage",command,support,"right-version").getStatusCode().value()).isEqualTo(200);
    }

    @Test @DisplayName("未知产品只能引用已审核通用知识")
    void unknownProductKnowledge() {
        long id = create();
        var command = triage(0);
        for (int reference : List.of(204,205)) {
            command.put("confirmedDiagnostics",List.of(Map.of("content","排查","claimType","HYPOTHESIS","knowledgeRefs",List.of(reference))));
            assertCode(post("/tickets/"+id+"/triage",command,support,"bad-kb-"+reference),422,"KNOWLEDGE_REFERENCE_INVALID");
        }
        command.put("confirmedDiagnostics",List.of(Map.of("content","排查","claimType","HYPOTHESIS","knowledgeRefs",List.of(201))));
        assertThat(post("/tickets/"+id+"/triage",command,support,"general-kb").getStatusCode().value()).isEqualTo(200);
    }

    @Test @DisplayName("请求校验与缺失幂等键拒绝")
    void requestValidation() {
        assertCode(post("/tickets",createCommand(1,1),customerA,null),400,"IDEMPOTENCY_KEY_REQUIRED");
        var invalid = createCommand(1,1); invalid.put("impact","INVALID");
        assertCode(post("/tickets",invalid,customerA,"invalid"),400,"REQUEST_INVALID");
        assertNoCreatedData();
    }

    private String login(String username) {
        var response = post("/auth/login",Map.of("username",username,"password","S1Demo!2026"),null,null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("token").asText();
    }
    private long create() {
        var response = post("/tickets",createCommand(1,1),customerA,UUID.randomUUID().toString());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().path("ticketId").asLong();
    }
    private Map<String,Object> createCommand(long customer, long contract) {
        return new LinkedHashMap<>(Map.of("customerId",customer,"contractId",contract,"title","数据库连接故障","description","升级后提示数据库连接超时","serviceType","DATABASE_SUPPORT","impact","HIGH","urgency","HIGH"));
    }
    private Map<String,Object> message(int version, String content) {
        return new LinkedHashMap<>(Map.of("expectedTicketVersion",version,"content",content));
    }
    private Map<String,Object> triage(int version) {
        return new LinkedHashMap<>(Map.of("expectedTicketVersion",version,"confirmedCategory","DATABASE_CONNECTION",
                "confirmedFacts",List.of(Map.of("field","symptom","value","数据库连接超时","sourceQuote","提示数据库连接超时")),
                "confirmedFollowups",List.of(Map.of("field","productVersion","question","请提供升级前后版本")),"confirmedDiagnostics",List.of()));
    }
    private ResponseEntity<JsonNode> get(String path, String token) { return request(HttpMethod.GET,path,null,token,null); }
    private ResponseEntity<JsonNode> post(String path, Object body, String token, String key) { return request(HttpMethod.POST,path,body,token,key); }
    private ResponseEntity<JsonNode> request(HttpMethod method, String path, Object body, String token, String key) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        if (key != null) headers.set("Idempotency-Key",key);
        return http.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table,Long.class); }
    private void assertNoCreatedData() {
        for (String table : List.of("service_ticket","ticket_sla_instance","business_audit_log","idempotency_request")) assertThat(count(table)).as(table).isZero();
    }
    private void assertCode(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }
}
