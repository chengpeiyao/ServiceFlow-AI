package com.serviceflow.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.serviceflow.experiment.ExperimentData.*;

public final class ExperimentMetrics {
    public record Score(boolean categoryCorrect, int factCorrect, int factWrong, int factMissing,
                        int requiredQuestionAsked, int requiredQuestionMissing, int extraQuestionCandidates,
                        int duplicateQuestions, int knownFieldQuestions, int citedSuggestions, int uncitedSuggestions,
                        Integer relevantRetrieved, Integer relevantTotal, String semanticEvidenceSupport) {}
    public static Score score(Case c, JsonNode accepted, List<Knowledge> retrieval, String arm) {
        Map<String, String> predicted = new HashMap<>(); Set<String> asked = new HashSet<>();
        int correct = 0, wrong = 0, missing = 0, duplicates = 0, known = 0, cited = 0, uncited = 0;
        if (accepted != null) {
            for (JsonNode f : accepted.path("facts")) predicted.put(f.path("field").asText(), f.path("value").asText());
            for (JsonNode q : accepted.path("questions")) {
                String field = q.path("field").asText();
                if (!asked.add(field)) duplicates++;
                if (!c.gold().requiredQuestions().contains(field) && (c.gold().facts().containsKey(field)
                        || (field.equals("product") && !c.input().product().isBlank())
                        || (field.equals("version") && !c.input().version().isBlank()))) known++;
            }
            for (JsonNode s : accepted.path("suggestions")) if (s.path("knowledgeId").asText().isBlank()) uncited++; else cited++;
        }
        for (var fact : predicted.entrySet()) {
            if (normalize(fact.getValue()).equals(normalize(c.gold().facts().get(fact.getKey())))) correct++; else wrong++;
        }
        for (var fact : c.gold().facts().entrySet())
            if (!normalize(fact.getValue()).equals(normalize(predicted.get(fact.getKey())))) missing++;
        int required = (int) c.gold().requiredQuestions().stream().filter(asked::contains).count();
        int extra = (int) asked.stream().filter(q -> !c.gold().requiredQuestions().contains(q)).count();
        Set<String> ids = new HashSet<>(); retrieval.forEach(k -> ids.add(k.id()));
        return new Score(accepted != null && c.gold().category().equals(accepted.path("category").asText()),
                correct, wrong, missing, required, c.gold().requiredQuestions().size() - required, extra, duplicates, known, cited, uncited,
                arm.equals("A1") ? null : (int) c.gold().relevantKnowledge().stream().filter(ids::contains).count(),
                arm.equals("A1") ? null : c.gold().relevantKnowledge().size(), "UNSCORED_HUMAN_REVIEW_REQUIRED");
    }
    private static String normalize(String s) { return s == null ? "\u0000" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    public static Map<String, Integer> mechanical(Input input, JsonNode output, List<Knowledge> evidence) {
        int facts = 0, quotedFacts = 0, citations = 0, realCitations = 0;
        if (output != null) {
            for (JsonNode f : output.path("facts")) {
                facts++; String quote = f.path("sourceQuote").asText();
                if (!quote.isBlank() && input.description().contains(quote) && quote.contains(f.path("value").asText())) quotedFacts++;
            }
            for (JsonNode s : output.path("suggestions")) {
                String id = s.path("knowledgeId").asText(), quote = s.path("sourceQuote").asText();
                if (id.isBlank()) continue;
                citations++;
                if (!quote.isBlank() && evidence.stream().anyMatch(k -> k.id().equals(id) && TriageEngine.applicable(k, input) && k.text().contains(quote))) realCitations++;
            }
        }
        return Map.of("factQuoteTotal", facts, "factQuoteExact", quotedFacts, "citationTotal", citations, "citationExistsApplicableAndExact", realCitations);
    }
    public static Map<String, Object> aggregate(List<JsonNode> rows) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (String arm : List.of("A0", "A1", "A2")) {
            List<JsonNode> r = rows.stream().filter(n -> arm.equals(n.path("arm").asText())).toList();
            if (r.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cases", r.size()); m.put("categoryCorrect", r.stream().filter(n -> n.path("metrics").path("categoryCorrect").asBoolean()).count());
            m.put("categoryWrong", r.size() - ((Number)m.get("categoryCorrect")).longValue());
            m.put("categoryInsufficientCases", r.stream().filter(n -> n.path("acceptedOutput").path("categoryInsufficient").asBoolean()).count());
            for (String key : List.of("factQuoteTotal", "factQuoteExact", "citationTotal", "citationExistsApplicableAndExact"))
                m.put(key, r.stream().mapToInt(n -> n.path("mechanicalChecks").path(key).asInt()).sum());
            Map<String, Integer> failures = new TreeMap<>();
            Map<String, Map<String, Integer>> confusion = new TreeMap<>();
            for (JsonNode row : r) {
                if (!row.path("status").asText().equals("SUCCESS")) failures.merge(row.path("status").asText(), 1, Integer::sum);
                row.path("validationErrors").forEach(e -> failures.merge("VALIDATION:" + e.asText(), 1, Integer::sum));
                String predicted = row.path("acceptedOutput").path("category").asText("REJECTED");
                confusion.computeIfAbsent(row.path("goldCategory").asText(), k -> new TreeMap<>()).merge(predicted, 1, Integer::sum);
            }
            m.put("failures", failures); m.put("confusion", confusion);
            double f1Sum = 0;
            for (String category : List.of("DATABASE_CONNECTION", "NETWORK", "APPLICATION", "ACCOUNT_PERMISSION", "CONFIGURATION", "OTHER")) {
                int tp = 0, fp = 0, fn = 0;
                for (JsonNode row : r) {
                    boolean gold = category.equals(row.path("goldCategory").asText());
                    boolean predicted = category.equals(row.path("acceptedOutput").path("category").asText());
                    if (gold && predicted) tp++; else if (gold) fn++; else if (predicted) fp++;
                }
                f1Sum += 2.0 * tp / Math.max(1, 2 * tp + fp + fn);
            }
            m.put("macroF1Over6Categories", f1Sum / 6);
            for (String metric : List.of("factCorrect", "factWrong", "factMissing", "requiredQuestionAsked", "requiredQuestionMissing",
                    "extraQuestionCandidates", "duplicateQuestions", "knownFieldQuestions", "citedSuggestions", "uncitedSuggestions"))
                m.put(metric, r.stream().mapToInt(n -> n.path("metrics").path(metric).asInt()).sum());
            double tp = ((Number)m.get("factCorrect")).doubleValue(), fp = ((Number)m.get("factWrong")).doubleValue(), fn = ((Number)m.get("factMissing")).doubleValue();
            m.put("factPrecisionExact", tp / Math.max(1, tp + fp)); m.put("factRecallExact", tp / Math.max(1, tp + fn));
            m.put("factF1Exact", 2 * tp / Math.max(1, 2 * tp + fp + fn));
            if (!arm.equals("A1")) {
                int got = r.stream().mapToInt(n -> n.path("metrics").path("relevantRetrieved").asInt()).sum();
                int total = r.stream().mapToInt(n -> n.path("metrics").path("relevantTotal").asInt()).sum();
                m.put("relevantRetrieved", got); m.put("relevantTotal", total); m.put("retrievalRecallAt3", total == 0 ? null : (double)got / total);
            }
            List<Long> latency = r.stream().map(n -> n.path("elapsedMs").asLong()).sorted().toList();
            m.put("p50Ms", latency.get((latency.size() - 1) / 2)); m.put("p95Ms", latency.get((int)Math.ceil(latency.size() * .95) - 1));
            long knownIn = 0, knownOut = 0; int unknownUsage = 0, attempts = 0, inputKnown = 0, outputKnown = 0;
            for (JsonNode row : r) for (JsonNode a : row.path("attempts")) {
                attempts++; if (a.path("inputTokens").isNull() || a.path("outputTokens").isNull()) unknownUsage++;
                if (a.path("inputTokens").isNumber()) { knownIn += a.path("inputTokens").asLong(); inputKnown++; }
                if (a.path("outputTokens").isNumber()) { knownOut += a.path("outputTokens").asLong(); outputKnown++; }
            }
            m.put("attempts", attempts); m.put("knownInputTokens", inputKnown == 0 ? null : knownIn);
            m.put("knownOutputTokens", outputKnown == 0 ? null : knownOut); m.put("attemptsWithUnknownUsage", unknownUsage);
            m.put("totalCost", null); m.put("costStatus", "NOT_PRICED_OR_USAGE_INCOMPLETE");
            var estimates = r.stream().map(n -> n.path("listPriceEstimate")).filter(JsonNode::isNumber).toList();
            m.put("knownListPriceEstimateSubtotal", estimates.isEmpty() ? null : estimates.stream().map(JsonNode::decimalValue).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
            m.put("listPriceEstimateTotal", estimates.size() == r.size() ? m.get("knownListPriceEstimateSubtotal") : null);
            m.put("semanticEvidenceSupport", null); m.put("questionQualityHumanScore", null);
            all.put(arm, m);
        }
        return all;
    }
    private ExperimentMetrics() {}
}
