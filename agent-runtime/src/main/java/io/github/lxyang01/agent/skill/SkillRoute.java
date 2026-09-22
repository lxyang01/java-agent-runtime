package io.github.lxyang01.agent.skill;

import java.util.List;
import java.util.Map;

/** routes.json 中一条路由;outputContract 为 null 或 {sections, gate_terms, ...}。 */
public record SkillRoute(
    String skillName,
    List<Trigger> triggers,
    List<String> allowedTools,
    List<SkillCompletionRule> completionRules,
    int priority,
    Map<String, Object> outputContract) {
}
