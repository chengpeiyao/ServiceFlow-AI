package com.serviceflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.serviceflow.experiment.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static com.serviceflow.experiment.ExperimentData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用假模型验证组合、拒绝和降级，不计作真实模型效果。 */
class HybridTriageTest {
    private static Corpus corpus;
    private HttpServer server;
    @BeforeAll static void data() throws Exception { corpus = Corpus.load(Path.of("../experiments/s1-ai")); }
    @AfterEach void stop() { if (server != null) server.stop(0); }
    private ModelClient client(JsonNode output) throws Exception {
        ObjectNode envelope = JSON.createObjectNode().put("model", "test-model");
        envelope.putArray("choices").addObject().put("finish_reason", "stop").putObject("message").put("content", output.toString());
        envelope.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 30);
        byte[] body = envelope.toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        return new ModelClient(new ModelClient.Config("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-model", "KEY", "max_tokens", "json_object", "0", 2), null);
    }
    @Test void rulePlanCannotDependOnFamilyOrGold() {
        Input input = corpus.dev().getFirst().input();
        Input other = new Input("different-id", "different-family", input.description(), input.product(), input.version());
        assertEquals(HybridTriage.plan(input), HybridTriage.plan(other));
        assertEquals(TriageEngine.baseline(input, List.of()).path("questions"), HybridTriage.plan(input).path("questions"));
    }
    @Test void modelCannotChangeCategoryRemoveQuestionsOrMutateRaw() {
        Input input = corpus.dev().getFirst().input();
        ObjectNode model = TriageEngine.baseline(input, List.of());
        model.put("category", "NETWORK");
        model.putArray("questions").addObject().put("field", "logs").put("question", "请提供订单失败时脱敏的应用日志。");
        model.withArray("questions").addObject().put("field", "product").put("question", "请问产品名称？");
        ObjectNode original = model.deepCopy();
        var decision = HybridTriage.compose(input, List.of(), model, true);
        assertEquals("DATABASE_CONNECTION", decision.output().path("category").asText());
        assertEquals(HybridTriage.plan(input).path("questions").size(), decision.output().path("questions").size());
        assertEquals(List.of("product"), decision.ignoredModelQuestionFields());
        assertFalse(decision.fallbackQuestionFields().contains("logs"));
        assertEquals(original, model);
        assertEquals(List.of(), TriageEngine.validate(decision.output(), input, List.of(), corpus.schema()));
    }
    @Test void invalidFactsRejectWholeModelBeforeRuleFallback() throws Exception {
        Case c = corpus.dev().getFirst(); ObjectNode invalid = TriageEngine.baseline(c.input(), List.of());
        ((ObjectNode)invalid.path("facts").get(0)).put("sourceQuote", "模型编造的现场结论");
        var row = ExperimentCli.execute(c, "A3", corpus, client(invalid), null);
        assertEquals("RULE_FALLBACK", row.path("status").asText());
        assertEquals("VALIDATION_REJECTED", row.path("modelStatus").asText());
        assertFalse(row.path("modelCandidateAccepted").asBoolean());
        assertEquals(invalid, row.path("rawOutput"));
        assertTrue(row.path("validationErrors").toString().contains("FACT_QUOTE_MISMATCH"));
        assertEquals(TriageEngine.baseline(c.input(), TriageEngine.retrieve(c.input(), corpus.knowledge())), row.path("acceptedOutput"));
        assertEquals(100, row.path("attempts").get(0).path("inputTokens").asInt());
        JsonNode summary = JSON.valueToTree(ExperimentMetrics.aggregate(List.of(row))).path("A3");
        assertEquals(1, summary.path("ruleFallbackCases").asInt());
        assertEquals(0, summary.path("modelCandidateAcceptedCases").asInt());
        assertEquals(1, summary.path("failures").path("RULE_FALLBACK").asInt());
    }
    @Test void successfulHybridKeepsModelFactsButScoresFinalRuleQuestions() throws Exception {
        Case c = corpus.dev().getFirst(); ObjectNode model = TriageEngine.baseline(c.input(), List.of());
        model.put("category", "NETWORK"); model.putArray("questions");
        var row = ExperimentCli.execute(c, "A3", corpus, client(model), null);
        assertEquals("SUCCESS", row.path("status").asText());
        assertTrue(row.path("modelCandidateAccepted").asBoolean());
        assertEquals("NETWORK", row.path("rawOutput").path("category").asText());
        assertEquals("DATABASE_CONNECTION", row.path("acceptedOutput").path("category").asText());
        assertEquals(model.path("facts"), row.path("acceptedOutput").path("facts"));
        assertEquals(2, row.path("metrics").path("requiredQuestionAsked").asInt());
        assertEquals(0, row.path("rawOutput").path("questions").size());
    }
    @Test void transportFailureRetainsRulesAndUnknownTokenUsage() throws Exception {
        Case c = corpus.dev().getFirst();
        var connected = client(TriageEngine.baseline(c.input(), List.of()));
        server.stop(0); server = null;
        var row = ExperimentCli.execute(c, "A3", corpus, connected, null);
        assertEquals("RULE_FALLBACK", row.path("status").asText());
        assertEquals("TRANSPORT_OR_ENVELOPE_ERROR", row.path("modelStatus").asText());
        assertTrue(row.path("acceptedOutput").isObject());
        assertTrue(row.path("attempts").get(0).path("inputTokens").isNull());
        assertTrue(row.path("listPriceEstimate").isNull());
    }
    @Test void hybridRequestOnlyAddsDeterministicPlanAndDedicatedInstructions() throws Exception {
        Case c = corpus.dev().getFirst(); var connected = client(TriageEngine.baseline(c.input(), List.of()));
        var request = connected.request(c.input(), List.of(), corpus.schema(), true);
        var visible = JSON.readTree(request.path("messages").get(1).path("content").asText());
        assertEquals(HybridTriage.plan(c.input()), visible.path("ruleDecision"));
        assertFalse(visible.has("gold")); assertFalse(visible.has("family")); assertFalse(visible.has("requiredQuestions"));
        assertEquals(1024, request.path("max_tokens").asInt());
        assertFalse(request.path("messages").get(0).path("content").asText().contains("最多四项"));
        assertFalse(connected.request(c.input(), List.of(), corpus.schema()).toString().contains("ruleDecision"));
    }
}
