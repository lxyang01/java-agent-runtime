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

    /**
     * 执行前迁移(approved → executing):收窄「执行后崩溃」重放窗口。缺省直通
     * (记录型后端无迁移);持久后端覆写为条件 UPDATE。恢复侧遇到 executing
     * 拒绝自动重放,由人工/超时回收处理(副作用幂等由下游工具保证)。
     */
    default ApprovalRecord markExecuting(String approvalId) {
        return get(approvalId);
    }

    ApprovalRecord markExecution(String approvalId, boolean succeeded, String error);

    int deleteForConversation(String sessionId);
}
