package io.github.lxyang01.agent.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 单次请求的动态契约:参数硬约束 + 输出章节门禁。逐字对齐 Python contracts.RequestContract。
 */
public record RequestContract(
    List<ArgumentConstraint> argumentConstraints,
    List<OutputSection> requiredSections) {

    public RequestContract {
        argumentConstraints = List.copyOf(argumentConstraints);
        requiredSections = List.copyOf(requiredSections);
    }

    public static RequestContract empty() {
        return new RequestContract(List.of(), List.of());
    }

    /** 工具参数校验:针对带 limit 属性的工具,强制用户原话编译出的上限。 */
    public List<String> toolViolations(String toolName, Map<String, Object> arguments,
                                       Map<String, Object> schema) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)
            || !properties.containsKey("limit")) {
            return List.of();
        }
        String role = RequestContracts.toolRole(toolName);
        List<String> violations = new ArrayList<>();
        for (ArgumentConstraint constraint : argumentConstraints) {
            if (!constraint.toolRoles().contains(role) || !"limit".equals(constraint.path())) {
                continue;
            }
            Object actual = arguments.get("limit");
            if (actual == null) {
                violations.add("参数 limit 必须显式提供，且不能超过 " + constraint.value());
            } else if (!(actual instanceof Number)) {
                violations.add("参数 limit 必须是数字");
            } else if ("lte".equals(constraint.operator())
                && ((Number) actual).doubleValue() > constraint.value()) {
                violations.add("参数 limit=" + actual + " 超过用户要求的最大值 "
                    + constraint.value());
            }
        }
        return violations;
    }

    /** 最终回答缺失的必需章节名(按契约顺序)。 */
    public List<String> missingSections(String answer) {
        Set<String> keys = RequestContracts.jsonKeys(answer);
        List<String> missing = new ArrayList<>();
        for (OutputSection section : requiredSections) {
            if (!RequestContracts.hasSection(answer, keys, section)) {
                missing.add(section.name());
            }
        }
        return missing;
    }

    /** 注入模型上下文的契约文本。 */
    public String promptText() {
        List<String> parts = new ArrayList<>();
        if (!argumentConstraints.isEmpty()) {
            List<String> rendered = new ArrayList<>();
            for (ArgumentConstraint item : argumentConstraints) {
                String roles = String.join("/", item.toolRoles());
                rendered.add(roles + "." + item.path() + " <= " + item.value()
                    + "（必须显式传参）");
            }
            parts.add("动态参数契约：" + String.join("；", rendered) + "。");
        }
        if (!requiredSections.isEmpty()) {
            parts.add("最终输出结构契约：必须包含这些可识别章节："
                + String.join("、", requiredSections.stream().map(OutputSection::name).toList())
                + "。可使用 Markdown 标题或同名 JSON 字段。");
        }
        return String.join("\n", parts);
    }

    static Set<String> lowerAll(Iterable<String> values) {
        Set<String> lowered = new LinkedHashSet<>();
        for (String value : values) {
            lowered.add(value.toLowerCase(Locale.ROOT));
        }
        return lowered;
    }

    static Pattern sectionLinePattern(String label) {
        return Pattern.compile("(?im)^\\s*(?:#{1,6}\\s*|[-*]\\s*|\\*\\*)?"
            + Pattern.quote(label) + "(?:\\*\\*)?\\s*(?::|：|$)");
    }
}
