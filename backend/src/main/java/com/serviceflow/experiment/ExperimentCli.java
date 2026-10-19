package com.serviceflow.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.serviceflow.experiment.ExperimentData.*;

public final class ExperimentCli {
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !Set.of("--root", "--split", "--arms", "--config", "--confirm-fixed").contains(args[i]) || options.put(args[i], args[i+1]) != null)
                throw new IllegalArgumentException("参数格式：--root 路径 --split dev|fixed --arms A0[,A1,A2] [--config 路径] [--confirm-fixed yes]");
        }
        Path root = Path.of(options.getOrDefault("--root", "experiments/s1-ai")).toAbsolutePath().normalize();
        String split = options.getOrDefault("--split", "dev");
        if (!Set.of("dev", "fixed").contains(split)) throw new IllegalArgumentException("split 仅允许 dev/fixed");
        if (split.equals("fixed") && !"yes".equals(options.get("--confirm-fixed"))) throw new IllegalArgumentException("固定集只用于冻结后检查，请传 --confirm-fixed yes；看结果调参后必须另建检查集");
        List<String> arms = Arrays.stream(options.getOrDefault("--arms", "A0").split(",")).distinct().toList();
        if (arms.isEmpty() || !Set.of("A0", "A1", "A2").containsAll(arms)) throw new IllegalArgumentException("未知实验分组");
        Corpus corpus = Corpus.load(root);
        ModelClient client = null; ModelClient.Config config = null; ExperimentPricing pricing = null;
        if (arms.stream().anyMatch(a -> !a.equals("A0"))) {
            Properties p = new Properties();
            if (options.containsKey("--config")) try (var reader = Files.newBufferedReader(Path.of(options.get("--config")))) { p.load(reader); }
            pricing = ExperimentPricing.from(p);
            config = new ModelClient.Config(value(p, "endpoint", "SERVICEFLOW_AI_ENDPOINT", ""), value(p, "model", "SERVICEFLOW_AI_MODEL", ""),
                    value(p, "keyEnvironment", "SERVICEFLOW_AI_KEY_ENV", "SERVICEFLOW_AI_API_KEY"),
                    value(p, "tokenParameter", "SERVICEFLOW_AI_TOKEN_PARAMETER", "max_completion_tokens"),
                    value(p, "responseFormat", "SERVICEFLOW_AI_RESPONSE_FORMAT", "json_schema"),
                    value(p, "temperature", "SERVICEFLOW_AI_TEMPERATURE", ""), 20);
            client = new ModelClient(config, System.getenv(config.keyEnvironment()));
        }
        String runId = Instant.now().toString().replaceAll("[:.]", "-") + "-" + UUID.randomUUID().toString().substring(0,8);
        Path run = root.resolve("runs").resolve(runId); Files.createDirectories(run);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("startedAt", Instant.now().toString()); manifest.put("split", split); manifest.put("arms", arms);
        manifest.put("modelConfig", config); manifest.put("concurrency", 2); manifest.put("maximumRetries", 1);
        manifest.put("pricing", pricing); manifest.put("priceEstimateLimit", "仅按配置标价估算，不扣缓存折扣，不代表账单；用量不完整时整条估算为 NULL");
        manifest.put("maxOutputTokens", 1024); manifest.put("knowledgeCharacters", 8000); manifest.put("toolRounds", 0);
        manifest.put("fixtureStatus", "SYNTHETIC_PROVISIONAL_LABELS_AND_DEMO_KNOWLEDGE_REQUIRE_DOMAIN_REVIEW");
        manifest.put("billing", "未知用量及未配置价格均为 NULL；失败或超时可能已计费，不等同零费用");
        Map<String, String> hashes = new TreeMap<>();
        for (String file : List.of("data/dev.jsonl", "data/fixed.jsonl", "data/knowledge.jsonl", "output.schema.json"))
            hashes.put(file, sha256(Files.readAllBytes(root.resolve(file))));
        manifest.put("artifact", ExperimentCli.class.getProtectionDomain().getCodeSource().getLocation().toString());
        for (Class<?> type : List.of(ExperimentData.class, TriageEngine.class, ModelClient.class, ExperimentMetrics.class, ExperimentCli.class, ExperimentPricing.class, ReviewSummaryCli.class))
            classHashes(type, hashes);
        manifest.put("sha256", hashes); write(run.resolve("manifest.json"), manifest);
        List<Case> cases = split.equals("dev") ? corpus.dev() : corpus.fixed();
        List<Callable<ObjectNode>> jobs = new ArrayList<>();
        final ModelClient frozenClient = client;
        final ExperimentPricing frozenPricing = pricing;
        // 每个案例内交替 A1/A2 的顺序，降低固定执行顺序造成的缓存或时段偏差。
        int index = 0;
        for (Case c : cases) {
            List<String> order = new ArrayList<>(arms); if ((index++ & 1) == 1) Collections.reverse(order);
            for (String arm : order) jobs.add(() -> execute(c, arm, corpus, frozenClient, frozenPricing));
        }
        List<JsonNode> rows = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2);
             var result = Files.newBufferedWriter(run.resolve("results.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             var review = Files.newBufferedWriter(run.resolve("human-review.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            // 最多两个在途任务；逐条刷盘以保留中断前证据，不重放已付费的整批请求。
            Deque<Future<ObjectNode>> active = new ArrayDeque<>(); int next = 0;
            while (next < jobs.size() || !active.isEmpty()) {
                while (active.size() < 2 && next < jobs.size()) active.add(pool.submit(jobs.get(next++)));
                ObjectNode row = active.removeFirst().get(); rows.add(row);
                ObjectNode human = JSON.createObjectNode().put("reviewId", UUID.randomUUID().toString());
                human.set("input", row.path("input")); human.set("output", row.path("rawOutput")); human.set("evidence", row.path("retrievedEvidence"));
                human.putNull("factsSemanticCorrect"); human.putNull("questionRelevance"); human.putNull("evidenceSupportsSuggestion");
                human.putNull("preconditionsSatisfied"); human.putNull("factHypothesisConfusion"); human.putNull("reviewer"); human.putNull("notes");
                // 文件不展示组名；通过 reviewId 在结果文件中映射回实验组。证据内容可能使组别可推断，不能声称完全盲评。
                row.put("reviewId", human.path("reviewId").asText());
                result.write(row.toString()); result.newLine(); result.flush();
                review.write(human.toString()); review.newLine(); review.flush();
            }
        }
        // 带 reviewId 的索引单独保存，保持逐条原始结果不覆盖。
        List<Map<String, String>> mapping = rows.stream().map(r -> Map.of("reviewId", r.path("reviewId").asText(), "caseId", r.path("caseId").asText(), "arm", r.path("arm").asText())).toList();
        write(run.resolve("review-index.json"), mapping);
        write(run.resolve("summary.json"), ExperimentMetrics.aggregate(rows));
        manifest.put("completedAt", Instant.now().toString()); write(run.resolve("completed.json"), manifest);
        System.out.println("实验记录：" + run); System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(ExperimentMetrics.aggregate(rows)));
    }
    private static ObjectNode execute(Case c, String arm, Corpus corpus, ModelClient client, ExperimentPricing pricing) throws Exception {
        long start = System.nanoTime();
        List<Knowledge> retrieved = arm.equals("A1") ? List.of() : TriageEngine.retrieve(c.input(), corpus.knowledge());
        List<Knowledge> evidence = new ArrayList<>(); int characters = 0;
        for (Knowledge k : retrieved) { int size = JSON.writeValueAsString(k).length(); if (characters + size <= 8000) { evidence.add(k); characters += size; } }
        JsonNode raw; String content = null, failure = null; List<ModelClient.Attempt> attempts = List.of();
        if (arm.equals("A0")) raw = TriageEngine.baseline(c.input(), evidence);
        else { var result = client.complete(c.input(), evidence, corpus.schema()); raw = result.output(); content = result.rawContent(); failure = result.failure(); attempts = result.attempts(); }
        List<String> validation = raw == null ? List.of() : TriageEngine.validate(raw, c.input(), evidence, corpus.schema());
        JsonNode accepted = failure == null && validation.isEmpty() ? raw : null;
        ObjectNode row = JSON.createObjectNode().put("caseId", c.input().id()).put("arm", arm).put("goldCategory", c.gold().category());
        row.set("input", JSON.valueToTree(c.input())); row.set("retrievedEvidence", JSON.valueToTree(evidence));
        row.set("rawOutput", raw); row.put("rawContent", content); row.set("acceptedOutput", accepted);
        row.put("status", failure != null ? failure : validation.isEmpty() ? "SUCCESS" : "VALIDATION_REJECTED");
        row.set("validationErrors", JSON.valueToTree(validation)); row.set("attempts", JSON.valueToTree(attempts));
        row.set("metrics", JSON.valueToTree(ExperimentMetrics.score(c, accepted, evidence, arm)));
        row.set("mechanicalChecks", JSON.valueToTree(ExperimentMetrics.mechanical(c.input(), raw, evidence)));
        row.put("elapsedMs", Duration.ofNanos(System.nanoTime() - start).toMillis()); row.putNull("cost");
        row.set("listPriceEstimate", JSON.valueToTree(pricing == null ? null : pricing.estimate(attempts)));
        return row;
    }
    private static String value(Properties p, String key, String environment, String fallback) { return p.getProperty(key, System.getenv().getOrDefault(environment, fallback)); }
    private static void write(Path path, Object value) throws Exception { Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void classHashes(Class<?> type, Map<String, String> hashes) throws Exception {
        String name = type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream("/" + name)) { hashes.put(name, sha256(Objects.requireNonNull(stream).readAllBytes())); }
        for (Class<?> nested : type.getDeclaredClasses()) classHashes(nested, hashes);
    }
    private ExperimentCli() {}
}
