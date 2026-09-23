package io.github.lxyang01.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lxyang01.agent.skill.SkillActivation;
import io.github.lxyang01.agent.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 契约编译器与章节目录;正则/别名/消息逐字contracts.py。 */
public final class RequestContracts {

    private static final Pattern MAX_ROWS =
        Pattern.compile("最多\\s*(?:返回|读取|给出)?\\s*(\\d+)\\s*条([^，。；,;]{0,10})");
    private static final Pattern TOP_ITEMS =
        Pattern.compile("(?:返回|给出|列出)?\\s*前\\s*(\\d+)\\s*项");

    private static final Set<String> SAMPLES_ROLE = Set.of(
        "feedback_samples", "bill_samples", "get_samples");
    private static final Set<String> QUERY_ROLE = Set.of(
        "feedback_search", "bill_search", "query");
    private static final Set<String> ANOMALIES_ROLE = Set.of(
        "feedback_anomalies", "bill_anomalies", "detect_anomalies");

    private static final Map<String, OutputSection> SECTION_CATALOG = buildCatalog();

    private RequestContracts() {}

    public static RequestContract compile(String userInput, List<SkillActivation> activeSkills) {
        List<ArgumentConstraint> constraints = new ArrayList<>();
        Matcher matcher = MAX_ROWS.matcher(userInput);
        while (matcher.find()) {
            int maximum = Integer.parseInt(matcher.group(1));
            String tail = matcher.group(2);
            String prefix = userInput.substring(Math.max(0, matcher.start() - 24),
                matcher.start());
            String prefixTail = prefix.substring(Math.max(0, prefix.length() - 8));
            List<String> roles = new ArrayList<>();
            if (tail.contains("样本") || prefixTail.contains("样本")) {
                roles.add("samples");
            }
            if (prefix.contains("搜索") || prefix.contains("检索") || prefix.contains("查找")) {
                roles.add("query");
            }
            if (roles.isEmpty()) {
                roles.add("query");
            }
            constraints.add(new ArgumentConstraint(
                List.copyOf(new LinkedHashSet<>(roles)), "limit", "lte", maximum,
                matcher.group(0)));
        }
        matcher = TOP_ITEMS.matcher(userInput);
        while (matcher.find()) {
            constraints.add(new ArgumentConstraint(
                List.of("anomalies"), "limit", "lte", Integer.parseInt(matcher.group(1)),
                matcher.group(0)));
        }

        // 同一角色集合多次约束:最严者胜(先出现者平局保留)
        Map<RolePath, ArgumentConstraint> strictest = new LinkedHashMap<>();
        for (ArgumentConstraint item : constraints) {
            RolePath key = new RolePath(item.toolRoles(), item.path());
            ArgumentConstraint current = strictest.get(key);
            if (current == null || item.value() < current.value()) {
                strictest.put(key, item);
            }
        }

        List<OutputSection> sections = new ArrayList<>();
        for (SkillActivation skill : activeSkills) {
            Map<String, Object> contract = skill.outputContract();
            if (contract == null) {
                continue;
            }
            List<?> gateTerms = contract.get("gate_terms") instanceof List<?> terms ? terms : List.of();
            boolean gated = gateTerms.stream().anyMatch(term -> userInput.contains(String.valueOf(term)));
            if (!gated) {
                continue;
            }
            List<?> names = contract.get("sections") instanceof List<?> list ? list : List.of();
            for (Object name : names) {
                OutputSection section = SECTION_CATALOG.get(String.valueOf(name));
                if (section != null) {
                    sections.add(section);
                }
            }
        }
        return new RequestContract(List.copyOf(strictest.values()),
            List.copyOf(new LinkedHashSet<>(sections)));
    }

    /** 工具角色:末段名经三套命名别名归一(legacy feedback_* / 本地 bill_* / MCP bill.*)。 */
    public static String toolRole(String toolName) {
        String leaf = toolName.substring(toolName.lastIndexOf('.') + 1);
        if (SAMPLES_ROLE.contains(leaf)) {
            return "samples";
        }
        if (QUERY_ROLE.contains(leaf)) {
            return "query";
        }
        if (ANOMALIES_ROLE.contains(leaf)) {
            return "anomalies";
        }
        return leaf;
    }

    public static List<String> outputSectionsMissing(String answer, List<String> requiredNames) {
        List<OutputSection> sections = requiredNames.stream()
            .map(SECTION_CATALOG::get).toList();
        return new RequestContract(List.of(), sections).missingSections(answer);
    }

    static Set<String> jsonKeys(String answer) {
        JsonNode root;
        try {
            root = Json.readTree(answer);
        } catch (RuntimeException e) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(root, keys);
        return keys;
    }

    static boolean hasSection(String answer, Set<String> keys, OutputSection section) {
        for (String label : section.labels()) {
            String lowered = label.toLowerCase(Locale.ROOT);
            if (keys.contains(lowered)) {
                return true;
            }
            if (RequestContract.sectionLinePattern(label).matcher(answer).find()) {
                return true;
            }
        }
        return false;
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                keys.add(entry.getKey().toLowerCase(Locale.ROOT));
                collectKeys(entry.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    private static Map<String, OutputSection> buildCatalog() {
        Map<String, OutputSection> catalog = new LinkedHashMap<>();
        put(catalog, new OutputSection("执行摘要",
            List.of("执行摘要", "摘要", "summary", "executive_summary")));
        put(catalog, new OutputSection("数据事实",
            List.of("数据事实", "数据概览", "data_facts", "facts")));
        put(catalog, new OutputSection("异常问题",
            List.of("异常问题", "异常变化", "anomalies")));
        put(catalog, new OutputSection("代表性样本",
            List.of("代表性样本", "样本观察", "samples", "evidence_samples")));
        put(catalog, new OutputSection("行动建议",
            List.of("行动建议", "下一步建议", "建议的验证动作", "recommendations", "action_items")));
        put(catalog, new OutputSection("数据局限",
            List.of("数据局限", "局限与风险", "风险和待确认事项", "limitations", "risks")));
        // monthly-guard-report 固定四章(与 skills/monthly-guard-report/SKILL.md 一致)
        put(catalog, new OutputSection("支出事实",
            List.of("支出事实", "支出概览", "data_facts", "facts")));
        put(catalog, new OutputSection("异常清单",
            List.of("异常清单", "异常问题", "异常变化", "anomalies")));
        put(catalog, new OutputSection("根因推测",
            List.of("根因推测", "根因分析", "原因推测", "root_cause", "hypotheses")));
        put(catalog, new OutputSection("行动计划",
            List.of("行动计划", "行动建议", "下一步行动", "action_plan", "action_items",
                "recommendations")));
        return catalog;
    }

    private static void put(Map<String, OutputSection> catalog, OutputSection section) {
        catalog.put(section.name(), section);
    }

    private record RolePath(List<String> roles, String path) {}
}
