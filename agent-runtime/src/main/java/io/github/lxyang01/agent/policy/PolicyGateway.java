package io.github.lxyang01.agent.policy;

/**
 * 工具调用的唯一门禁。FORBIDDEN 抛异常(引擎转工具错误);
 * HIGH_WRITE 或 requiresApproval → ApprovalRequired(引擎暂停存检查点)。
 */
public final class PolicyGateway {

    private PolicyGateway() {}

    public static PolicyVerdict enforce(ToolPolicy policy, String toolName) {
        if (policy.riskLevel() == RiskLevel.FORBIDDEN) {
            throw new PolicyException("tool is forbidden by policy: " + toolName);
        }
        if (policy.requiresApproval() || policy.riskLevel() == RiskLevel.HIGH_WRITE) {
            return ApprovalRequired.INSTANCE;
        }
        return Allowed.INSTANCE;
    }
}
