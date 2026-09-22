package io.github.lxyang01.agent.store;

import io.github.lxyang01.agent.policy.ToolPolicy;
import java.util.List;
import java.util.Map;

/**
 * 审批存储端口。decide 的并发语义由实现保证「恰好一次」
 * (PG 实现 = 条件 UPDATE + updateCount 裁决;禁止先查后写的竞态写法)。
 */
public interface ApprovalStore {

    ApprovalRecord request(String sessionId, String traceId, int step, String toolName,
                           Map<String, Object> arguments, ToolPolicy policy,
                           Map<String, Object> checkpoint);

    ApprovalRecord get(String approvalId);

    List<ApprovalRecord> list(String sessionId, List<String> statuses, int limit);

    ApprovalRecord decide(String approvalId, boolean approved, String decidedBy, String note);

    ApprovalRecord markExecution(String approvalId, boolean succeeded, String error);

    int deleteForConversation(String sessionId);
}
