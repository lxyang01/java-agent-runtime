package io.github.lxyang01.agent.policy;

/** 策略裁决:只读/低写放行,高写或 requiresApproval 暂停审批;forbidden 走异常(见 PolicyGateway)。 */
public sealed interface PolicyVerdict permits Allowed, ApprovalRequired {
}
