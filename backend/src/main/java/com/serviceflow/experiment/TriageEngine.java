package com.serviceflow.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.serviceflow.experiment.ExperimentData.*;

public final class TriageEngine {
    private static final LinkedHashMap<String, List<String>> CATEGORIES = new LinkedHashMap<>();
    public static final LinkedHashMap<String, String> QUESTIONS = new LinkedHashMap<>();
    static {
        CATEGORIES.put("DATABASE_CONNECTION", List.of("死锁", "连接池", "SQL", "数据库", "锁等待"));
        CATEGORIES.put("ACCOUNT_PERMISSION", List.of("403", "401", "登录", "权限", "证书"));
        CATEGORIES.put("NETWORK", List.of("DNS", "网络", "断连", "连接超时", "网关", "502", "504"));
        CATEGORIES.put("CONFIGURATION", List.of("时钟", "时间戳", "阈值", "单位", "配置"));
        CATEGORIES.put("OTHER", List.of("设备", "传感器", "心跳", "离线"));
        CATEGORIES.put("APPLICATION", List.of("重复", "不一致", "丢失", "负数", "库存", "慢", "延迟", "卡顿", "CPU", "内存", "耗时", "异常", "报错", "失败", "白屏", "崩溃"));
        QUESTIONS.put("product", "请确认产品名称或产品代码。");
        QUESTIONS.put("version", "请提供准确的产品版本。");
        QUESTIONS.put("startTime", "故障从什么时间开始？");
        QUESTIONS.put("scope", "哪些用户或功能受到影响？");
        QUESTIONS.put("errorCode", "是否有错误码或完整错误提示？");
        QUESTIONS.put("change", "发生前是否进行过发布或配置变更？");
        QUESTIONS.put("frequency", "故障发生频率如何？");
        QUESTIONS.put("logs", "请提供脱敏后的相关日志。");
        QUESTIONS.put("reproduction", "请描述最小复现步骤。");
    }
    public static boolean applicable(Knowledge k, Input i) {
        if (!"APPROVED_DEMO".equals(k.reviewStatus())) return false;
        if ("GENERAL".equals(k.product())) return k.versions().equals(List.of("ALL"));
        return !i.product().isBlank() && k.product().equals(i.product())
                && (k.versions().contains("ALL") || (!i.version().isBlank() && k.versions().contains(i.version())));
    }
    public static List<Knowledge> retrieve(Input i, List<Knowledge> knowledge) {
        String text = i.description().toLowerCase(Locale.ROOT);
        return knowledge.stream().filter(k -> applicable(k, i))
                .filter(k -> score(text, k) > 0)
                .sorted(Comparator.<Knowledge>comparingInt(k -> score(text, k)).reversed().thenComparing(Knowledge::id))
                .limit(3).toList();
    }
    private static int score(String text, Knowledge k) {
        return (int) k.keywords().stream().filter(w -> text.contains(w.toLowerCase(Locale.ROOT))).count();
    }
    public static ObjectNode baseline(Input i, List<Knowledge> evidence) {
        ObjectNode out = JSON.createObjectNode();
        String text = i.description();
        String category = CATEGORIES.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(text::contains)).map(Map.Entry::getKey).findFirst().orElse("OTHER");
        out.put("category", category);
        out.put("categoryInsufficient", CATEGORIES.values().stream().flatMap(List::stream).noneMatch(text::contains));
        ArrayNode facts = out.putArray("facts");
        fact(facts, "symptom", text, text);
        var errors = Pattern.compile("(?i)\\b(?:HTTP\\s*)?[45]\\d{2}\\b|\\b[A-Z][A-Z_]{3,}\\b|死锁|锁等待|连接池耗尽").matcher(text);
        Set<String> known = new HashSet<>(List.of("symptom"));
        if (errors.find()) { fact(facts, "errorCode", errors.group(), errors.group()); known.add("errorCode"); }
        var time = Pattern.compile("(?:今天|昨天)?(?:上午|下午)?\\d{1,2}[:：]\\d{2}").matcher(text);
        if (time.find()) { fact(facts, "startTime", time.group(), time.group()); known.add("startTime"); }
        for (String scope : List.of("全部用户", "所有用户", "部分用户", "单个用户", "全部设备", "一台设备"))
            if (text.contains(scope)) { fact(facts, "scope", scope, scope); known.add("scope"); break; }
        out.putArray("hypotheses");
        ArrayNode questions = out.putArray("questions");
        QUESTIONS.forEach((field, question) -> {
            if ((field.equals("product") && !i.product().isBlank()) || (field.equals("version") && !i.version().isBlank()) || known.contains(field)) return;
            questions.addObject().put("field", field).put("question", question);
        });
        ArrayNode suggestions = out.putArray("suggestions");
        for (Knowledge k : evidence) {
            ObjectNode s = suggestions.addObject(); s.put("text", k.text()); s.put("knowledgeId", k.id()); s.put("sourceQuote", k.text());
            s.set("preconditions", JSON.valueToTree(k.preconditions()));
        }
        out.put("evidenceInsufficient", evidence.isEmpty());
        return out;
    }
    private static void fact(ArrayNode facts, String field, String value, String quote) {
        facts.addObject().put("field", field).put("value", value).put("sourceQuote", quote);
    }
    public static List<String> validate(JsonNode out, Input i, List<Knowledge> evidence, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        checkSchema(out, schema, "$", errors);
        if (!errors.isEmpty()) return errors;
        Set<String> facts = new HashSet<>();
        for (JsonNode f : out.path("facts")) {
            if (!i.description().contains(f.path("sourceQuote").asText()) || !f.path("sourceQuote").asText().contains(f.path("value").asText()))
                errors.add("FACT_QUOTE_MISMATCH");
            if (!facts.add(f.path("field").asText())) errors.add("DUPLICATE_FACT_FIELD");
            String field = f.path("field").asText(), value = f.path("value").asText();
            if (!i.description().contains(f.path("sourceQuote").asText())
                    && ((field.equals("product") && value.equals(i.product())) || (field.equals("version") && value.equals(i.version()))))
                errors.add("METADATA_QUOTE_NOT_IN_DESCRIPTION");
        }
        Set<String> questions = new HashSet<>();
        for (JsonNode question : out.path("questions"))
            if (!questions.add(question.path("field").asText())) errors.add("DUPLICATE_QUESTION_FIELD");
        for (JsonNode s : out.path("suggestions")) {
            String id = s.path("knowledgeId").asText(), quote = s.path("sourceQuote").asText();
            if (id.isEmpty()) { if (!quote.isEmpty()) errors.add("QUOTE_WITHOUT_SOURCE"); continue; }
            Optional<Knowledge> k = evidence.stream().filter(x -> x.id().equals(id)).findFirst();
            if (k.isEmpty()) { errors.add("UNKNOWN_OR_UNRETRIEVED_SOURCE"); continue; }
            if (!applicable(k.get(), i)) errors.add("INAPPLICABLE_SOURCE");
            if (quote.isBlank() || !k.get().text().contains(quote)) errors.add("SOURCE_QUOTE_MISMATCH");
            Set<String> conditions = new HashSet<>(); s.path("preconditions").forEach(x -> conditions.add(x.asText()));
            if (!conditions.containsAll(k.get().preconditions())) errors.add("MISSING_PRECONDITION");
        }
        if (evidence.isEmpty() && !out.path("evidenceInsufficient").asBoolean()) errors.add("EVIDENCE_ABSENCE_NOT_DECLARED");
        return errors;
    }
    // 仅实现随项目冻结的 Schema 子集；额外字段一律拒绝，阻止业务字段混入。
    private static void checkSchema(JsonNode value, JsonNode schema, String path, List<String> errors) {
        String type = schema.path("type").asText();
        boolean matches = switch (type) { case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "boolean" -> value.isBoolean(); default -> false; };
        if (!matches) { errors.add("SCHEMA_TYPE:" + path); return; }
        if (schema.has("enum") && !contains(schema.path("enum"), value)) errors.add("SCHEMA_ENUM:" + path);
        if (value.isObject()) {
            schema.path("required").forEach(n -> { if (!value.has(n.asText())) errors.add("SCHEMA_REQUIRED:" + path + "." + n.asText()); });
            value.fieldNames().forEachRemaining(name -> {
                if (!schema.path("properties").has(name)) errors.add("SCHEMA_EXTRA:" + path + "." + name);
                else checkSchema(value.get(name), schema.path("properties").get(name), path + "." + name, errors);
            });
        } else if (value.isArray()) {
            if (value.size() > schema.path("maxItems").asInt(100)) errors.add("SCHEMA_SIZE:" + path);
            for (JsonNode item : value) checkSchema(item, schema.path("items"), path + "[]", errors);
        } else if (value.isTextual() && (value.asText().length() < schema.path("minLength").asInt(0)
                || value.asText().length() > schema.path("maxLength").asInt(4000))) errors.add("SCHEMA_LENGTH:" + path);
    }
    private static boolean contains(JsonNode array, JsonNode value) { for (JsonNode n : array) if (n.equals(value)) return true; return false; }
    private TriageEngine() {}
}
