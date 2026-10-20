package com.serviceflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.serviceflow.experiment.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.serviceflow.experiment.ExperimentData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本地假 HTTP 仅验证传输约束，不作为真实模型实验结果。 */
class ExperimentTest {
    private static Corpus corpus;
    private HttpServer server;
    @BeforeAll static void data() throws Exception { corpus = Corpus.load(Path.of("../experiments/s1-ai")); }
    @AfterEach void stop() { if (server != null) server.stop(0); }
    private Input input(String product, String version) { return new Input("test", "secret-gold-family", "连接池耗尽，今天09:15全部用户提交失败。", product, version); }
    private ObjectNode output(Input input) { return TriageEngine.baseline(input, List.of()); }
    private ModelClient client(int timeout) {
        return new ModelClient(new ModelClient.Config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", "test-model", "TEST_KEY", "max_tokens", "json_object", "0", timeout), "test-secret");
    }
    private void serve(int status, String body, AtomicInteger count) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            count.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] data = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, data.length);
            try (var stream = exchange.getResponseBody()) { stream.write(data); }
        }); server.start();
    }
    private String envelope(String finish, Object content, boolean usage) throws Exception {
        ObjectNode n = JSON.createObjectNode().put("id", "local-test").put("model", "served-test");
        n.putArray("choices").addObject().put("finish_reason", finish).putObject("message").put("content", content.toString());
        if (usage) n.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20);
        return n.toString();
    }
    @Test void corpusHasExpectedCountsAndDisjointFamilies() {
        assertEquals(20, corpus.dev().size()); assertEquals(40, corpus.fixed().size()); assertEquals(24, corpus.knowledge().size());
        Set<String> dev = new HashSet<>(); corpus.dev().forEach(c -> dev.add(c.input().family()));
        assertTrue(corpus.fixed().stream().noneMatch(c -> dev.contains(c.input().family())));
    }
    @Test void allBaselineOutputsPassSharedValidation() {
        var cases = new ArrayList<>(corpus.dev()); cases.addAll(corpus.fixed());
        for (var c : cases) {
            var evidence = TriageEngine.retrieve(c.input(), corpus.knowledge());
            assertEquals(List.of(), TriageEngine.validate(TriageEngine.baseline(c.input(), evidence), c.input(), evidence, corpus.schema()), c.input().id());
        }
    }
    @Test void versionMatchIsExactAndDraftExcluded() {
        var evidence = TriageEngine.retrieve(input("COMS", "1.2"), corpus.knowledge());
        assertTrue(evidence.stream().anyMatch(k -> k.id().equals("C01")));
        assertTrue(evidence.stream().noneMatch(k -> Set.of("C02", "DRAFT01").contains(k.id())));
    }
    @Test void unknownVersionOnlyGetsAllVersions() {
        assertTrue(TriageEngine.retrieve(input("COMS", ""), corpus.knowledge()).stream().allMatch(k -> k.versions().equals(List.of("ALL"))));
    }
    @Test void unknownProductGetsOnlyGeneralEvenWhenTextClaimsComs() {
        assertTrue(TriageEngine.retrieve(input("", ""), corpus.knowledge()).stream().allMatch(k -> k.product().equals("GENERAL")));
    }
    @Test void businessFieldsAreRejected() {
        Input i = input("", ""); var out = output(i); out.put("priority", "P1");
        assertTrue(TriageEngine.validate(out, i, List.of(), corpus.schema()).stream().anyMatch(e -> e.contains("SCHEMA_EXTRA")));
    }
    @Test void inventedFactQuoteIsRejected() {
        Input i = input("", ""); var out = output(i); ((ObjectNode)out.path("facts").get(0)).put("sourceQuote", "服务器已断电");
        assertTrue(TriageEngine.validate(out, i, List.of(), corpus.schema()).contains("FACT_QUOTE_MISMATCH"));
    }
    @Test void explicitMetadataCannotMasqueradeAsDescriptionQuotes() {
        Input i = input("COMS", "1.2"); var out = output(i);
        var facts = out.putArray("facts");
        facts.addObject().put("field", "product").put("value", "COMS").put("sourceQuote", "COMS");
        facts.addObject().put("field", "version").put("value", "1.2").put("sourceQuote", "1.2");
        var errors = TriageEngine.validate(out, i, List.of(), corpus.schema());
        assertEquals(2, Collections.frequency(errors, "FACT_QUOTE_MISMATCH"));
        assertEquals(2, Collections.frequency(errors, "METADATA_QUOTE_NOT_IN_DESCRIPTION"));
    }
    @Test void productAndVersionMayBeQuotedWhenActuallyPresentInDescription() {
        Input i = new Input("test", "test", "COMS 1.2 登录异常", "COMS", "1.2"); var out = output(i);
        var facts = out.putArray("facts");
        facts.addObject().put("field", "product").put("value", "COMS").put("sourceQuote", "COMS 1.2");
        facts.addObject().put("field", "version").put("value", "1.2").put("sourceQuote", "COMS 1.2");
        assertEquals(List.of(), TriageEngine.validate(out, i, List.of(), corpus.schema()));
    }
    @Test void repeatedSymptomFactsAreStillRejectedRatherThanSilentlyMerged() {
        Input i = input("", ""); var out = output(i);
        int before = out.path("facts").size();
        out.withArray("facts").addObject().put("field", "symptom").put("value", "连接池耗尽").put("sourceQuote", "连接池耗尽");
        assertTrue(TriageEngine.validate(out, i, List.of(), corpus.schema()).contains("DUPLICATE_FACT_FIELD"));
        assertEquals(before + 1, out.path("facts").size());
    }
    @Test void duplicateQuestionFieldsAreRejectedWithoutDroppingModelOutput() {
        Input i = input("", ""); var out = output(i);
        int before = out.path("questions").size();
        out.withArray("questions").addObject().put("field", "scope").put("question", "是否所有用户都有问题？");
        out.withArray("questions").addObject().put("field", "scope").put("question", "是否仅部分用户有问题？");
        assertTrue(TriageEngine.validate(out, i, List.of(), corpus.schema()).contains("DUPLICATE_QUESTION_FIELD"));
        assertEquals(before + 2, out.path("questions").size());
    }
    @Test void rawCategoryCorrectnessIsSeparateFromAcceptedOutput() {
        var row = JSON.createObjectNode().put("arm", "A1").put("status", "VALIDATION_REJECTED").put("goldCategory", "APPLICATION");
        row.putObject("rawOutput").put("category", "APPLICATION"); row.putNull("acceptedOutput");
        row.putObject("metrics").put("categoryCorrect", false);
        var summary = JSON.valueToTree(ExperimentMetrics.aggregate(List.of(row))).path("A1");
        assertEquals(1, summary.path("rawCategoryCorrect").asInt()); assertEquals(0, summary.path("categoryCorrect").asInt());
        assertEquals(1, summary.path("rejectedCases").asInt()); assertEquals(0, summary.path("acceptedCategoryWrong").asInt());
    }
    @Test void inventedKnowledgeIsRejected() {
        Input i = input("", ""); var out = output(i);
        out.withArray("suggestions").addObject().put("text", "建议").put("knowledgeId", "FAKE").put("sourceQuote", "不存在").putArray("preconditions");
        assertTrue(TriageEngine.validate(out, i, List.of(), corpus.schema()).contains("UNKNOWN_OR_UNRETRIEVED_SOURCE"));
    }
    @Test void missingPreconditionsAndWrongQuotesAreRejected() {
        Input i = input("COMS", "1.2"); var kb = TriageEngine.retrieve(i, corpus.knowledge()); var out = TriageEngine.baseline(i, kb);
        ObjectNode first = (ObjectNode)out.path("suggestions").get(0); first.put("sourceQuote", "无条件重启"); first.putArray("preconditions");
        var errors = TriageEngine.validate(out, i, kb, corpus.schema());
        assertTrue(errors.contains("SOURCE_QUOTE_MISMATCH")); assertTrue(errors.contains("MISSING_PRECONDITION"));
    }
    @Test void a1AndA2OnlyDifferInEvidenceAndDoNotSeeLabelsOrFamily() throws Exception {
        serve(200, "{}", new AtomicInteger()); var client = client(2); Input i = input("COMS", "1.2");
        var a1 = client.request(i, List.of(), corpus.schema()); var a2 = client.request(i, TriageEngine.retrieve(i, corpus.knowledge()), corpus.schema());
        assertFalse(a1.toString().contains(i.family())); assertFalse(a1.toString().contains("requiredQuestions"));
        var visible = JSON.readTree(a1.path("messages").get(1).path("content").asText());
        assertEquals(i.description(), visible.path("customerInput").path("description").asText());
        assertEquals(1, visible.path("customerInput").size());
        assertEquals(i.product(), visible.path("readOnlyReportedContext").path("reportedProduct").asText());
        assertEquals(i.version(), visible.path("readOnlyReportedContext").path("reportedVersion").asText());
        var data = (ObjectNode)JSON.readTree(a2.path("messages").get(1).path("content").asText()); data.putArray("retrievedEvidence");
        ((ObjectNode)a2.path("messages").get(1)).put("content", data.toString()); assertEquals(a1, a2);
    }
    @Test void successfulHttpPreservesUsageAndServedModel() throws Exception {
        serve(200, envelope("stop", output(input("", "")), true), new AtomicInteger());
        var r = client(2).complete(input("", ""), List.of(), corpus.schema());
        assertNull(r.failure()); assertEquals(100L, r.attempts().getFirst().inputTokens()); assertEquals("served-test", r.attempts().getFirst().servedModel());
    }
    @Test void missingUsageStaysNull() throws Exception {
        serve(200, envelope("stop", output(input("", "")), false), new AtomicInteger());
        assertNull(client(2).complete(input("", ""), List.of(), corpus.schema()).attempts().getFirst().inputTokens());
    }
    @Test void rateLimitRetriesExactlyOnceAndDoesNotLogResponseBody() throws Exception {
        AtomicInteger count = new AtomicInteger(); serve(429, "secret-server-body", count);
        var r = client(2).complete(input("", ""), List.of(), corpus.schema());
        assertEquals(2, count.get()); assertEquals(2, r.attempts().size()); assertFalse(JSON.writeValueAsString(r).contains("secret-server-body"));
    }
    @Test void invalidRequestDoesNotRetry() throws Exception {
        AtomicInteger count = new AtomicInteger(); serve(400, "bad", count);
        assertEquals("HTTP_400", client(2).complete(input("", ""), List.of(), corpus.schema()).failure()); assertEquals(1, count.get());
    }
    @Test void truncatedOutputRetainsUsageButIsNotAccepted() throws Exception {
        serve(200, envelope("length", "{", true), new AtomicInteger());
        var r = client(2).complete(input("", ""), List.of(), corpus.schema()); assertEquals("INCOMPLETE_OUTPUT", r.failure()); assertNull(r.output()); assertEquals(20L, r.attempts().getFirst().outputTokens());
    }
    @Test void invalidJsonIsNotRetriedAndUsageIsRetained() throws Exception {
        AtomicInteger count = new AtomicInteger(); serve(200, envelope("stop", "not-json", true), count);
        var r = client(2).complete(input("", ""), List.of(), corpus.schema()); assertEquals("INVALID_JSON", r.failure()); assertEquals(1, count.get()); assertEquals(100L, r.attempts().getFirst().inputTokens());
    }
    @Test void timeoutDoesNotRetryOrInventUsage() throws Exception {
        AtomicInteger count = new AtomicInteger(); server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> { count.incrementAndGet(); try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } exchange.close(); }); server.start();
        var r = client(1).complete(input("", ""), List.of(), corpus.schema()); assertEquals("TIMEOUT", r.failure()); assertEquals(1, count.get()); assertNull(r.attempts().getFirst().inputTokens());
    }
    @Test void insecureExternalEndpointsAndUrlCredentialsAreRejected() {
        for (String endpoint : List.of("http://example.com/v1/chat/completions", "https://key@example.com/v1/chat/completions", "https://example.com/v1/chat/completions?key=secret"))
            assertThrows(IllegalArgumentException.class, () -> new ModelClient.Config(endpoint, "test", "KEY", "max_tokens", "json_object", "", 20));
    }
    @Test void localBaseAddressesAreNormalizedToChatCompletions() {
        for (String host : List.of("127.0.0.1", "localhost", "[::1]", "LOCALHOST"))
            for (String path : List.of("", "/", "/v1", "/v1/", "/v1/chat/completions", "/v1/chat/completions/")) {
                var config = new ModelClient.Config("http://" + host + ":11434" + path, "local-model", "OLLAMA_API_KEY", "max_tokens", "json_object", "", 20);
                assertEquals("http://" + host + ":11434/v1/chat/completions", config.endpoint());
            }
    }
    @Test void localBaseAddressWorksWithoutKeyAndOmitsAuthorizationHeader() throws Exception {
        AtomicInteger calls = new AtomicInteger(), authorizationHeaders = new AtomicInteger();
        String body = envelope("stop", output(input("", "")), true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestHeaders().containsKey("Authorization")) authorizationHeaders.incrementAndGet();
            byte[] data = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, data.length);
            try (var stream = exchange.getResponseBody()) { stream.write(data); }
        }); server.start();
        var config = new ModelClient.Config("http://127.0.0.1:" + server.getAddress().getPort(), "local-model", "OLLAMA_API_KEY", "max_tokens", "json_object", "", 2);
        for (String key : Arrays.asList(null, "", " ")) {
            var result = new ModelClient(config, key).complete(input("", ""), List.of(), corpus.schema());
            assertNull(result.failure());
        }
        assertEquals(3, calls.get()); assertEquals(0, authorizationHeaders.get());
    }
    @Test void externalHttpsBaseIsNormalizedAndStillRequiresKey() {
        var config = new ModelClient.Config("https://model.service.test/v1/", "model", "KEY", "max_tokens", "json_object", "", 20);
        assertEquals("https://model.service.test/v1/chat/completions", config.endpoint());
        assertThrows(IllegalArgumentException.class, () -> new ModelClient(config, null));
        assertDoesNotThrow(() -> new ModelClient(config, "test-key"));
    }
    @Test void httpExternalHostsAndLoopbackLookalikesRemainRejected() {
        for (String host : List.of("192.168.1.20", "model.service.test", "localhost.service.test", "127.0.0.1.service.test", "0.0.0.0"))
            assertThrows(IllegalArgumentException.class, () -> new ModelClient.Config("http://" + host + ":11434", "model", "KEY", "max_tokens", "json_object", "", 20));
    }
    @Test void invalidBaseAddressesAndLocalUrlCredentialsAreRejected() {
        for (String endpoint : List.of("", "localhost:11434", "https:///v1/chat/completions", "http://127.0.0.1:11434/api/chat", "http://key@localhost:11434", "http://localhost:11434?key=secret", "http://localhost:11434/#fragment"))
            assertThrows(IllegalArgumentException.class, () -> new ModelClient.Config(endpoint, "model", "KEY", "max_tokens", "json_object", "", 20));
    }
    @Test void exactMetricCountsDoNotPretendToBeSemanticJudgments() {
        var c = corpus.dev().getFirst(); var r = ExperimentMetrics.score(c, TriageEngine.baseline(c.input(), List.of()), List.of(), "A0");
        assertTrue(r.factCorrect() > 0); assertTrue(r.factMissing() > 0); assertEquals("UNSCORED_HUMAN_REVIEW_REQUIRED", r.semanticEvidenceSupport());
    }
    @Test void priceNeedsCompleteUsageAndDoesNotPretendUnknownIsFree() {
        var p = new ExperimentPricing(new BigDecimal("2"), new BigDecimal("8"), "CNY", "https://example.com/pricing", "2026-10-19");
        var known = new ModelClient.Attempt(1, 200, "SUCCESS", 5, 100L, 20L, "m", "r");
        var unknown = new ModelClient.Attempt(2, 429, "HTTP_429", 5, null, null, null, null);
        assertEquals(0, p.estimate(List.of(known)).compareTo(new BigDecimal("0.00036")));
        assertNull(p.estimate(List.of(known, unknown))); assertNull(p.estimate(List.of()));
    }
    @Test void conflictingVersionRequiresClarificationRatherThanKnownFieldPenalty() {
        var c = corpus.dev().stream().filter(x -> x.input().family().equals("version-contradiction")).findFirst().orElseThrow();
        var out = output(c.input()); out.putArray("questions").addObject().put("field", "version").put("question", "请确认冲突的版本");
        assertEquals(0, ExperimentMetrics.score(c, out, List.of(), "A1").knownFieldQuestions());
    }
    @Test void cliWritesReproducibleArtifactsAndRequiresFixedConfirmation(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve("datasets"));
        Files.createDirectory(root.resolve("knowledge"));
        for (String name : List.of("datasets/dev.jsonl", "datasets/fixed.jsonl", "knowledge/knowledge.jsonl", "output.schema.json"))
            Files.copy(Path.of("../experiments/s1-ai").resolve(name), root.resolve(name));
        assertThrows(IllegalArgumentException.class, () -> ExperimentCli.main(new String[]{"--root", root.toString(), "--split", "fixed"}));
        assertThrows(IllegalArgumentException.class, () -> ExperimentCli.main(new String[]{"--root", root.toString(), "--arms", "A0", "--concurrency", "3"}));
        ExperimentCli.main(new String[]{"--root", root.toString(), "--arms", "A0", "--concurrency", "1"});
        Path run;
        try (var dirs = Files.list(root.resolve("runs"))) { run = dirs.findFirst().orElseThrow(); }
        assertEquals(20, Files.readAllLines(run.resolve("results.jsonl")).size());
        assertEquals(20, Files.readAllLines(run.resolve("human-review.jsonl")).size());
        assertTrue(JSON.readTree(run.resolve("manifest.json").toFile()).path("sha256").size() > 4);
        assertEquals(ModelClient.PROMPT_VERSION, JSON.readTree(run.resolve("manifest.json").toFile()).path("promptVersion").asText());
        assertEquals(1, JSON.readTree(run.resolve("manifest.json").toFile()).path("concurrency").asInt());
        assertTrue(Files.exists(run.resolve("completed.json")));
        assertTrue(JSON.readTree(run.resolve("summary.json").toFile()).path("A0").path("semanticEvidenceSupport").isNull());
        Path reviewed = run.resolve("reviewed.jsonl");
        List<String> reviewRows = Files.readAllLines(run.resolve("human-review.jsonl"));
        ObjectNode first = (ObjectNode)JSON.readTree(reviewRows.getFirst());
        first.put("reviewer", "测试审核人").put("evidenceSupportsSuggestion", false); reviewRows.set(0, first.toString());
        Files.write(reviewed, reviewRows);
        var summary = JSON.valueToTree(ReviewSummaryCli.summarize(run, reviewed));
        assertEquals(1, summary.path("A0").path("evidenceSupportsSuggestion").path("falseCases").asInt());
        assertEquals(19, summary.path("A0").path("evidenceSupportsSuggestion").path("unscoredCases").asInt());
        first.put("input", "篡改原始资料"); reviewRows.set(0, first.toString()); Files.write(reviewed, reviewRows);
        assertThrows(IllegalArgumentException.class, () -> ReviewSummaryCli.summarize(run, reviewed));
    }
}
