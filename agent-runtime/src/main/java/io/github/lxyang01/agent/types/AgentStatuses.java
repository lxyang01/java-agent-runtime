package io.github.lxyang01.agent.types;

/** AgentResponse.status 的取值(API 序列化契约,与 参考实现字符串一致)。 */
public final class AgentStatuses {

    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String APPROVAL_PENDING = "approval_pending";
    public static final String REJECTED = "rejected";

    private AgentStatuses() {}
}
