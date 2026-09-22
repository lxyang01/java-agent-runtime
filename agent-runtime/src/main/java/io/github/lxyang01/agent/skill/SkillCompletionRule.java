package io.github.lxyang01.agent.skill;

import java.util.List;

/** 完成规则:命中触发词后要求的工具序列(单选 + 组内备选)。 */
public record SkillCompletionRule(
    List<Trigger> triggers,
    List<String> requiredTools,
    List<List<String>> requiredToolGroups) {
}
