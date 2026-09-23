package io.github.lxyang01.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lxyang01.agent.util.Json;
import io.github.lxyang01.agent.util.Strings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能路由:规则触发词评分(零 LLM)。评分公式、
 * 排序键、惰性加载、版本指纹、完成契约解析与全部校验错误消息。
 */
public final class SkillRuntime {

    private static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern EXPLICIT_SKILL =
        Pattern.compile("\\$([a-z0-9]+(?:-[a-z0-9]+)*)");

    private final SkillSource source;
    private final int maxActive;
    private final int maxSkillBytes;
    private final Map<String, SkillMetadata> metadata = new LinkedHashMap<>();
    private final Map<String, SkillRoute> routes = new LinkedHashMap<>();
    private String defaultSkill = "";

    public SkillRuntime(Path root) {
        this(new DirectorySkillSource(root), 2, 256_000);
    }

    public SkillRuntime(SkillSource source) {
        this(source, 2, 256_000);
    }

    public SkillRuntime(SkillSource source, int maxActive, int maxSkillBytes) {
        if (maxActive < 1) {
            throw new SkillException("max_active must be positive");
        }
        this.source = source;
        this.maxActive = maxActive;
        this.maxSkillBytes = maxSkillBytes;
        refresh();
    }

    public void refresh() {
        Map<String, SkillMetadata> discovered = new LinkedHashMap<>();
        for (SkillMetadata candidate : source.list()) {
            Map<String, String> fields = readFrontmatter(candidate);
            String name = fields.getOrDefault("name", "");
            String description = fields.getOrDefault("description", "");
            validateMetadata(name, description, candidate);
            if (discovered.containsKey(name)) {
                throw new SkillException("duplicate skill name: " + name);
            }
            discovered.put(name, new SkillMetadata(name, description, candidate.path()));
        }
        if (discovered.isEmpty()) {
            throw new SkillException("no skills found under: " + describeRoot());
        }
        metadata.clear();
        metadata.putAll(discovered);
        loadRoutes();
    }

    public List<SkillMetadata> catalog() {
        return List.copyOf(metadata.values());
    }

    public List<SkillActivation> activate(String userInput) {
        String text = userInput.strip();
        if (text.isEmpty()) {
            throw new SkillException("cannot route an empty request");
        }
        List<String> explicit = new ArrayList<>();
        Matcher matcher = EXPLICIT_SKILL.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            explicit.add(matcher.group(1));
        }
        Set<String> unknown = new TreeSet<>(explicit);
        unknown.removeAll(metadata.keySet());
        if (!unknown.isEmpty()) {
            throw new SkillException(
                "unknown explicitly requested skill: " + String.join(", ", unknown));
        }

        Map<String, Scored> candidates = new LinkedHashMap<>();
        for (String name : explicit) {
            candidates.put(name, new Scored(10_000, "explicit:$" + name));
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (SkillRoute route : routes.values()) {
            TriggerMatch match = matchTrigger(route.triggers(), lowered);
            if (match == null) {
                continue;
            }
            int score = 100 + route.priority() + match.score();
            Scored previous = candidates.get(route.skillName());
            if (previous == null || score > previous.score()) {
                candidates.put(route.skillName(), new Scored(score, "trigger:" + match.reason()));
            }
        }
        if (candidates.isEmpty() && !defaultSkill.isEmpty()) {
            candidates.put(defaultSkill, new Scored(1, "default"));
        }
        return candidates.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Scored>>comparingInt(
                    e -> -e.getValue().score())
                .thenComparing(Map.Entry::getKey))
            .limit(maxActive)
            .map(e -> loadActivation(e.getKey(), e.getValue().score(), e.getValue().reason(),
                lowered))
            .toList();
    }

    /** 激活技能的工具白名单与 AgentSpec 的交集(按 fallback 顺序);空交集报错。 */
    public static List<String> allowedTools(List<SkillActivation> activations,
                                            List<String> fallback) {
        if (activations.isEmpty()) {
            return List.copyOf(fallback);
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (SkillActivation activation : activations) {
            allowed.addAll(activation.allowedTools());
        }
        List<String> constrained = new ArrayList<>();
        for (String tool : fallback) {
            if (allowed.contains(tool)) {
                constrained.add(tool);
            }
        }
        if (constrained.isEmpty()) {
            String names = String.join(", ",
                activations.stream().map(SkillActivation::name).toList());
            throw new SkillException(
                "activated skills expose no tools allowed by AgentSpec: " + names);
        }
        return constrained;
    }

    // ---- 内部:路由加载与激活 ----

    private void loadRoutes() {
        String raw;
        try {
            raw = source.readRoutes();
        } catch (IOException e) {
            throw new SkillException("cannot load skill routes: " + e, e);
        }
        if (raw == null) {
            throw new SkillException("cannot load skill routes: missing routes.json");
        }
        var root = Json.readTree(raw);
        if (!root.isObject() || !root.get("routes").isArray()) {
            throw new SkillException("skills/routes.json must contain a routes array");
        }
        String defaultSkill = root.path("default_skill").asText("");
        if (!defaultSkill.isEmpty() && !metadata.containsKey(defaultSkill)) {
            throw new SkillException("default skill is not installed: " + defaultSkill);
        }
        Map<String, SkillRoute> parsed = new LinkedHashMap<>();
        for (var item : root.get("routes")) {
            if (!item.isObject()) {
                throw new SkillException("each skill route must be an object");
            }
            String name = item.path("skill").asText("");
            if (!metadata.containsKey(name)) {
                throw new SkillException("route references an uninstalled skill: " + name);
            }
            if (parsed.containsKey(name)) {
                throw new SkillException("duplicate route for skill: " + name);
            }
            List<Trigger> triggers = parseTriggers(item.get("triggers"),
                "invalid triggers for skill: " + name);
            List<String> allowedTools = parseStringList(item.get("allowed_tools"),
                "invalid allowed_tools for skill: " + name);
            var rulesNode = item.get("completion_rules");
            if (rulesNode != null && !rulesNode.isArray()) {
                throw new SkillException("invalid completion_rules for skill: " + name);
            }
            int priority = item.path("priority").asInt(0);
            if (item.has("priority")
                && (!item.get("priority").isInt() || priority < -100 || priority > 100)) {
                throw new SkillException("invalid route priority for skill: " + name);
            }
            List<SkillCompletionRule> rules = new ArrayList<>();
            Iterable<JsonNode> ruleItems = rulesNode == null ? List.of() : rulesNode;
            for (JsonNode rule : ruleItems) {
                rules.add(parseCompletionRule(rule, allowedTools, name));
            }
            Map<String, Object> outputContract = null;
            var contractNode = item.get("output_contract");
            if (contractNode != null && !contractNode.isNull()) {
                if (!contractNode.isObject()) {
                    throw new SkillException("invalid output_contract for skill: " + name);
                }
                var sections = contractNode.get("sections");
                var gateTerms = contractNode.get("gate_terms");
                if (sections == null || !sections.isArray() || sections.isEmpty()
                    || !streamAllTextual(sections)
                    || gateTerms == null || !gateTerms.isArray() || gateTerms.isEmpty()
                    || !streamAllTextual(gateTerms)) {
                    throw new SkillException(
                        "output_contract requires non-empty sections and gate_terms: " + name);
                }
                outputContract = Json.MAPPER.convertValue(contractNode,
                    new com.fasterxml.jackson.core.type.TypeReference<
                        LinkedHashMap<String, Object>>() {});
            }
            parsed.put(name, new SkillRoute(name, triggers,
                distinct(allowedTools), List.copyOf(rules), priority, outputContract));
        }
        Set<String> missing = new TreeSet<>(metadata.keySet());
        missing.removeAll(parsed.keySet());
        if (!missing.isEmpty()) {
            throw new SkillException("skills missing routing policy: " + String.join(", ", missing));
        }
        this.defaultSkill = defaultSkill;
        routes.clear();
        routes.putAll(parsed);
    }

    private SkillCompletionRule parseCompletionRule(JsonNode rule, List<String> allowedTools,
                                                    String skillName) {
        if (!rule.isObject()) {
            throw new SkillException("completion rule must be an object: " + skillName);
        }
        List<Trigger> ruleTriggers = parseTriggers(rule.get("triggers"),
            "invalid completion rule triggers for skill: " + skillName);
        List<String> requiredTools = parseStringList(rule.get("required_tools"),
            "invalid required_tools for skill: " + skillName);
        var groupsNode = rule.get("required_tool_groups");
        List<List<String>> groups = new ArrayList<>();
        if (groupsNode != null && !groupsNode.isNull()) {
            if (!groupsNode.isArray()) {
                throw new SkillException(
                    "invalid required_tool_groups for skill: " + skillName);
            }
            for (var group : groupsNode) {
                if (!group.isArray() || group.size() < 2 || !streamAllTextual(group)) {
                    throw new SkillException(
                        "invalid required_tool_groups for skill: " + skillName);
                }
                groups.add(distinct(parseJsonStrings(group)));
            }
        }
        Set<String> groupTools = new TreeSet<>();
        groups.forEach(groupTools::addAll);
        Set<String> unknownRequired = new TreeSet<>(requiredTools);
        unknownRequired.removeAll(allowedTools);
        if (!unknownRequired.isEmpty()) {
            throw new SkillException(
                "completion rule requires tools outside allowed_tools for " + skillName + ": "
                    + String.join(", ", unknownRequired));
        }
        groupTools.removeAll(allowedTools);
        if (!groupTools.isEmpty()) {
            throw new SkillException(
                "completion rule groups reference tools outside allowed_tools for " + skillName
                    + ": " + String.join(", ", groupTools));
        }
        return new SkillCompletionRule(ruleTriggers, distinct(requiredTools), List.copyOf(groups));
    }

    private SkillActivation loadActivation(String name, int score, String reason,
                                           String loweredInput) {
        SkillMetadata meta = metadata.get(name);
        String raw;
        try {
            raw = source.readSkill(name);
        } catch (IOException e) {
            throw new SkillException("cannot read skill " + name + ": " + e, e);
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > maxSkillBytes) {
            throw new SkillException("skill exceeds size limit: " + name);
        }
        String body = splitDocument(raw, meta.path());
        String instructions = body.strip();
        if (instructions.isEmpty()) {
            throw new SkillException("skill body is empty: " + name);
        }
        if (instructions.split("\n", -1).length > 500) {
            throw new SkillException("skill body exceeds 500 lines: " + name);
        }
        if (Strings.len(instructions) > 8000) {
            throw new SkillException("skill body exceeds 8000 chars: " + name);
        }
        String version = sha256Hex(raw).substring(0, 12);
        SkillRoute route = routes.get(name);

        List<SkillCompletionRule> matchedRules = route.completionRules().stream()
            .filter(rule -> matchTrigger(rule.triggers(), loweredInput) != null)
            .toList();
        List<String> requiredTools = distinct(matchedRules.stream()
            .flatMap(rule -> rule.requiredTools().stream()).toList());
        List<List<String>> requiredGroups = distinctLists(matchedRules.stream()
            .flatMap(rule -> rule.requiredToolGroups().stream()).toList());
        // 完成计划 = 每条命中规则的单选工具(solo) + 备选组,保序去重
        List<List<String>> collectedPlan = new ArrayList<>();
        for (SkillCompletionRule rule : matchedRules) {
            for (String tool : rule.requiredTools()) {
                collectedPlan.add(List.of(tool));
            }
            collectedPlan.addAll(rule.requiredToolGroups());
        }
        List<List<String>> plan = distinctLists(collectedPlan);
        return new SkillActivation(name, meta.description(), instructions, version, reason,
            score, route.allowedTools(), requiredTools, requiredGroups, plan,
            route.outputContract());
    }

    private record Scored(int score, String reason) {}

    private record TriggerMatch(int score, String reason) {}

    /** 字符串触发词按子串;数组触发词按共现;返回最高得分命中。 */
    static TriggerMatch matchTrigger(List<Trigger> triggers, String lowered) {
        TriggerMatch best = null;
        for (Trigger trigger : triggers) {
            TriggerMatch candidate;
            if (trigger instanceof Word(String word)) {
                if (!lowered.contains(word.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                candidate = new TriggerMatch(Strings.len(word), word);
            } else if (trigger instanceof AllWords(List<String> words)) {
                boolean allPresent = words.stream()
                    .allMatch(word -> lowered.contains(word.toLowerCase(Locale.ROOT)));
                if (!allPresent) {
                    continue;
                }
                candidate = new TriggerMatch(
                    words.stream().mapToInt(Strings::len).sum(), String.join("+", words));
            } else {
                continue;
            }
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    private Map<String, String> readFrontmatter(SkillMetadata candidate) {
        String raw;
        try {
            // 统一经 SkillSource 读取(目录/类路径源同构),不再直连文件系统
            raw = source.readSkill(candidate.name());
        } catch (IOException e) {
            throw new SkillException("cannot read skill metadata " + candidate.path() + ": " + e, e);
        }
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            throw new SkillException(
                "SKILL.md must start with YAML frontmatter: " + candidate.path());
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new SkillException("unterminated YAML frontmatter: " + candidate.path());
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i < end; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!line.contains(":")) {
                throw new SkillException(
                    "unsupported frontmatter line in " + candidate.path() + ": " + line);
            }
            int colon = line.indexOf(':');
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.length() >= 2 && value.charAt(0) == value.charAt(value.length() - 1)
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
            fields.put(key, value);
        }
        return fields;
    }

    private static String splitDocument(String raw, Path path) {
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            throw new SkillException("SKILL.md must start with YAML frontmatter: " + path);
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new SkillException("unterminated YAML frontmatter: " + path);
        }
        return String.join("\n", java.util.Arrays.asList(lines).subList(end + 1, lines.length));
    }

    private void validateMetadata(String name, String description, SkillMetadata candidate) {
        if (name == null || !SKILL_NAME.matcher(name).matches() || name.length() > 64) {
            throw new SkillException(
                "invalid skill name in " + candidate.path() + ": " + name);
        }
        if (!candidate.path().getParent().getFileName().toString().equals(name)) {
            throw new SkillException(
                "skill directory must match name '" + name + "': "
                    + candidate.path().getParent().getFileName());
        }
        if (description == null || description.isEmpty() || description.length() > 1024) {
            throw new SkillException("invalid skill description for " + name);
        }
    }

    private static List<Trigger> parseTriggers(JsonNode node, String errorMessage) {
        if (node == null) {
            return List.of();          // 缺省空触发词列表(rule.get("triggers", []))
        }
        if (!node.isArray()) {
            throw new SkillException(errorMessage);
        }
        List<Trigger> triggers = new ArrayList<>();
        for (var item : node) {
            if (item.isTextual() && !item.asText().isEmpty()) {
                triggers.add(new Word(item.asText()));
            } else if (item.isArray() && item.size() >= 2 && streamAllTextual(item)) {
                triggers.add(new AllWords(parseJsonStrings(item)));
            } else {
                throw new SkillException(errorMessage);
            }
        }
        if (triggers.size() != node.size()) {
            throw new SkillException(errorMessage);
        }
        return triggers;
    }

    private static List<String> parseStringList(JsonNode node, String errorMessage) {
        if (node == null) {
            return List.of();          // 缺省空列表(rule.get(..., []))
        }
        if (!node.isArray()) {
            throw new SkillException(errorMessage);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isEmpty()) {
                throw new SkillException(errorMessage);
            }
            values.add(item.asText());
        }
        return values;
    }

    private static List<String> parseJsonStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static boolean streamAllTextual(JsonNode array) {
        boolean[] allTextual = {true};
        array.forEach(item -> {
            if (!item.isTextual() || item.asText().isEmpty()) {
                allTextual[0] = false;
            }
        });
        return allTextual[0];
    }

    private static <T> List<T> distinct(List<T> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static List<List<String>> distinctLists(List<List<String>> values) {
        LinkedHashSet<List<String>> seen = new LinkedHashSet<>();
        values.forEach(seen::add);
        return List.copyOf(seen);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(raw.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String describeRoot() {
        return source instanceof DirectorySkillSource directory
            ? directory.root().toString() : source.getClass().getSimpleName();
    }
}
