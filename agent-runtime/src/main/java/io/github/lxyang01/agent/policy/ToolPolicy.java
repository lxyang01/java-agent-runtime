package io.github.lxyang01.agent.policy;

/**
 * 工具策略。FORBIDDEN 一律强制 requiresApproval=true(ToolPolicy.__post_init__)。
 */
public record ToolPolicy(RiskLevel riskLevel, boolean requiresApproval, String reason) {

    public ToolPolicy {
        if (riskLevel == RiskLevel.FORBIDDEN) {
            requiresApproval = true;
        }
    }

    public static ToolPolicy read() {
        return new ToolPolicy(RiskLevel.READ, false, "Read-only operation");
    }

    public static ToolPolicy of(RiskLevel level, boolean requiresApproval, String reason) {
        return new ToolPolicy(level, requiresApproval, reason);
    }
}
