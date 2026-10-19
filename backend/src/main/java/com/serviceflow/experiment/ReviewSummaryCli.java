package com.serviceflow.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import static com.serviceflow.experiment.ExperimentData.*;

/** 汇总人工逐案例判定，空值保留为未评分，不推断语义正确性。 */
public final class ReviewSummaryCli {
    private static final List<String> FIELDS = List.of("factsSemanticCorrect", "questionRelevance", "evidenceSupportsSuggestion", "preconditionsSatisfied", "factHypothesisConfusion");
    public static Map<String, Object> summarize(Path run, Path reviewed) throws Exception {
        Map<String, JsonNode> original = new HashMap<>(), results = new HashMap<>();
        for (String line : Files.readAllLines(run.resolve("human-review.jsonl"))) {
            JsonNode n = JSON.readTree(line); original.put(n.path("reviewId").asText(), n);
        }
        for (String line : Files.readAllLines(run.resolve("results.jsonl"))) {
            JsonNode n = JSON.readTree(line); results.put(n.path("reviewId").asText(), n);
        }
        Map<String, Object> summary = new TreeMap<>(); Set<String> seen = new HashSet<>();
        Map<String, List<JsonNode>> grouped = new HashMap<>();
        for (String line : Files.readAllLines(reviewed)) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.readTree(line); String id = row.path("reviewId").asText();
            if (!seen.add(id) || !original.containsKey(id) || !results.containsKey(id)) throw new IllegalArgumentException("评分标识重复或未知");
            for (String field : List.of("input", "output", "evidence"))
                if (!row.path(field).equals(original.get(id).path(field))) throw new IllegalArgumentException("评分时禁止改动原始输入、输出与证据");
            for (String field : FIELDS) {
                JsonNode score = row.get(field);
                if (score == null || (!score.isNull() && !score.isBoolean())) throw new IllegalArgumentException("人工判定仅允许 true/false/null：" + field);
                if (score.isBoolean() && row.path("reviewer").asText("").isBlank()) throw new IllegalArgumentException("已评分项必须填写审核人");
            }
            grouped.computeIfAbsent(results.get(id).path("arm").asText(), k -> new ArrayList<>()).add(row);
        }
        Set<String> arms = new TreeSet<>(); results.values().forEach(n -> arms.add(n.path("arm").asText()));
        for (String arm : arms) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            long total = results.values().stream().filter(n -> n.path("arm").asText().equals(arm)).count();
            for (String field : FIELDS) {
                List<JsonNode> rows = grouped.getOrDefault(arm, List.of());
                long yes = rows.stream().filter(n -> n.path(field).isBoolean() && n.path(field).asBoolean()).count();
                long no = rows.stream().filter(n -> n.path(field).isBoolean() && !n.path(field).asBoolean()).count();
                metrics.put(field, Map.of("trueCases", yes, "falseCases", no, "unscoredCases", total - yes - no));
            }
            summary.put(arm, metrics);
        }
        return summary;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("参数：运行目录 已完成人工评分的 JSONL 文件");
        Path run = Path.of(args[0]);
        var summary = summarize(run, Path.of(args[1]));
        Path output = run.resolve("human-summary-" + UUID.randomUUID() + ".json");
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardOpenOption.CREATE_NEW);
        System.out.println("人工评分汇总：" + output);
    }
    private ReviewSummaryCli() {}
}
