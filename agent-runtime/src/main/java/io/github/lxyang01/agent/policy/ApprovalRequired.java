package io.github.lxyang01.agent.policy;

/** 需人工审批(单例);引擎写检查点暂停。 */
public record ApprovalRequired() implements PolicyVerdict {
    public static final ApprovalRequired INSTANCE = new ApprovalRequired();
}
