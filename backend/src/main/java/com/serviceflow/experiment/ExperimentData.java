package com.serviceflow.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 独立实验数据，不连接业务数据库，也不包含业务写入命令。 */
public final class ExperimentData {
    public static final ObjectMapper JSON = new ObjectMapper();
    public record Input(String id, String family, String description, String product, String version) {}
    public record Gold(String category, Map<String, String> facts, List<String> requiredQuestions,
                       List<String> relevantKnowledge) {}
    public record Case(Input input, Gold gold) {}
    public record Knowledge(String id, String product, List<String> versions, String reviewStatus,
                            List<String> keywords, List<String> preconditions, String text) {}
    public record Corpus(List<Case> dev, List<Case> fixed, List<Knowledge> knowledge, JsonNode schema) {
        public static Corpus load(Path root) throws Exception {
            List<Case> dev = read(root.resolve("datasets/dev.jsonl"), Case.class);
            List<Case> fixed = read(root.resolve("datasets/fixed.jsonl"), Case.class);
            List<Knowledge> kb = read(root.resolve("knowledge/knowledge.jsonl"), Knowledge.class);
            if (dev.size() != 20 || fixed.size() != 40) throw new IllegalArgumentException("案例数量必须为 20/40");
            Set<String> ids = new HashSet<>(), families = new HashSet<>(), knowledgeIds = new HashSet<>();
            for (Knowledge k : kb) {
                if (!knowledgeIds.add(k.id()) || k.text().isBlank() || k.keywords().isEmpty())
                    throw new IllegalArgumentException("知识标识重复或内容为空");
            }
            for (Case c : concat(dev, fixed)) {
                Input i = c.input();
                if (!ids.add(i.id()) || i.description().isBlank() || i.description().length() > 4000)
                    throw new IllegalArgumentException("案例标识重复或原文长度不合法");
                for (String value : c.gold().facts().values())
                    if (!i.description().contains(value)) throw new IllegalArgumentException("标注事实必须逐字出现在原文：" + i.id());
                if (!knowledgeIds.containsAll(c.gold().relevantKnowledge())) throw new IllegalArgumentException("标注引用不存在");
            }
            dev.forEach(c -> families.add(c.input().family()));
            if (fixed.stream().anyMatch(c -> families.contains(c.input().family())))
                throw new IllegalArgumentException("开发集与检查集故障家族重叠");
            return new Corpus(dev, fixed, kb, JSON.readTree(root.resolve("output.schema.json").toFile()));
        }
    }
    private static <T> List<T> read(Path path, Class<T> type) throws Exception {
        List<T> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) if (!line.isBlank()) rows.add(JSON.readValue(line, type));
        return List.copyOf(rows);
    }
    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a); out.addAll(b); return out;
    }
    private ExperimentData() {}
}
