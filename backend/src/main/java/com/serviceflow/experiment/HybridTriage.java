package com.serviceflow.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.serviceflow.experiment.ExperimentData.*;

/** A3 的保守组合策略，规则负责字段集合，模型候选必须先通过完整校验。 */
public final class HybridTriage {
    public static final String RULE_VERSION = "a3-conservative-v0.1";
    public static final String PROMPT_VERSION = "s1-ai-hybrid-v0.1";
    public record Decision(ObjectNode output, boolean modelAccepted, List<String> fallbackQuestionFields,
                           List<String> ignoredModelQuestionFields) {}

    public static ObjectNode plan(Input input) {
        ObjectNode baseline = TriageEngine.baseline(input, List.of());
        ObjectNode plan = JSON.createObjectNode();
        plan.set("category", baseline.path("category"));
        plan.set("categoryInsufficient", baseline.path("categoryInsufficient"));
        plan.set("questions", baseline.path("questions").deepCopy());
        return plan;
    }

    public static Decision compose(Input input, List<Knowledge> evidence, JsonNode model, boolean modelAccepted) {
        ObjectNode rule = TriageEngine.baseline(input, evidence);
        List<String> fallback = new ArrayList<>(), ignored = new ArrayList<>();
        if (!modelAccepted) {
            rule.path("questions").forEach(q -> fallback.add(q.path("field").asText()));
            return new Decision(rule, false, fallback, ignored);
        }
        // 不修复非法模型事实；调用方必须在组合前通过原有来源和 Schema 校验。
        ObjectNode out = model.deepCopy();
        out.set("category", rule.path("category"));
        out.set("categoryInsufficient", rule.path("categoryInsufficient"));
        Map<String, JsonNode> wording = new HashMap<>();
        model.path("questions").forEach(q -> wording.put(q.path("field").asText(), q));
        var questions = out.putArray("questions");
        for (JsonNode q : rule.path("questions")) {
            String field = q.path("field").asText();
            JsonNode expression = wording.remove(field);
            questions.add(expression == null ? q.deepCopy() : expression.deepCopy());
            if (expression == null) fallback.add(field);
        }
        ignored.addAll(wording.keySet()); Collections.sort(ignored);
        return new Decision(out, true, fallback, ignored);
    }
    private HybridTriage() {}
}
