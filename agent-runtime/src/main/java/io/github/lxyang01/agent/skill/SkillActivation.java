package io.github.lxyang01.agent.skill;

import java.util.List;
import java.util.Map;

/** 激活产物:指令正文 + 版本 + 命中说明 + 工具边界 + 完成契约。 */
public record SkillActivation(
    String name,
    String description,
    String instructions,
    String version,
    String reason,
    int score,
    List<String> allowedTools,
    List<String> requiredTools,
    List<List<String>> requiredToolGroups,
    List<List<String>> requiredToolPlan,
    Map<String, Object> outputContract) {
}
