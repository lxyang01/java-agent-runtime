package io.github.lxyang01.agent.testing;

import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.store.ApprovalRecord;
import io.github.lxyang01.agent.store.ApprovalStore;
import io.github.lxyang01.agent.types.Timestamps;
import io.github.lxyang01.agent.util.Strings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 进程内审批存储;状态机与错误消息。 */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRecord> approvals = new LinkedHashMap<>();

    @Override
    public ApprovalRecord request(String sessionId, String traceId, int step, String toolName,
                                  Map<String, Object> arguments, ToolPolicy policy,
                                  Map<String, Object> checkpoint) {
        if (policy.riskLevel() == RiskLevel.FORBIDDEN) {
            throw new PolicyException("forbidden tool cannot request approval: " + toolName);
        }
        String approvalId = "POL-" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 12).toUpperCase();
        ApprovalRecord record = new ApprovalRecord(approvalId, sessionId, traceId, step,
            toolName, arguments, policy.riskLevel().wire(), policy.reason(), "pending",
            checkpoint, Timestamps.nowIso(), null, null, null, null, null);
        approvals.put(approvalId, record);
        return record;
    }

    @Override
    public ApprovalRecord get(String approvalId) {
        ApprovalRecord record = approvals.get(approvalId);
        if (record == null) {
            throw new PolicyException("approval not found: " + approvalId);
        }
        return record;
    }

    @Override
    public List<ApprovalRecord> list(String sessionId, List<String> statuses, int limit) {
        if (limit < 1 || limit > 500) {
            throw new PolicyException("approval list limit must be between 1 and 500");
        }
        return approvals.values().stream()
            .filter(record -> sessionId == null || sessionId.isEmpty()
                || record.sessionId().equals(sessionId))
            .filter(record -> statuses == null || statuses.isEmpty()
                || statuses.contains(record.status()))
            .sorted(Comparator.comparing(ApprovalRecord::requestedAt).reversed())
            .limit(limit)
            .toList();
    }

    @Override
    public ApprovalRecord decide(String approvalId, boolean approved, String decidedBy,
                                 String note) {
        String decider = decidedBy == null ? "" : decidedBy.strip();
        if (decider.isEmpty()) {
            throw new PolicyException("decided_by is required");
        }
        ApprovalRecord record = get(approvalId);
        if (!"pending".equals(record.status())) {
            throw new PolicyException(
                "approval is already " + record.status() + ": " + approvalId);
        }
        // 单线程内存实现:状态检查后直接改写(PG 实现由条件 UPDATE 保证并发恰好一次)
        ApprovalRecord updated = new ApprovalRecord(record.id(), record.sessionId(),
            record.traceId(), record.step(), record.toolName(), record.arguments(),
            record.riskLevel(), record.reason(), approved ? "approved" : "rejected",
            record.checkpoint(), record.requestedAt(), Timestamps.nowIso(), decider,
            note == null ? "" : note.strip(), record.executedAt(), record.executionError());
        approvals.put(approvalId, updated);
        return updated;
    }

    @Override
    public ApprovalRecord markExecution(String approvalId, boolean succeeded, String error) {
        ApprovalRecord record = get(approvalId);
        if (!"approved".equals(record.status())) {
            throw new PolicyException(
                "only approved requests can be executed: " + record.status());
        }
        ApprovalRecord updated = new ApprovalRecord(record.id(), record.sessionId(),
            record.traceId(), record.step(), record.toolName(), record.arguments(),
            record.riskLevel(), record.reason(), succeeded ? "executed" : "failed",
            record.checkpoint(), record.requestedAt(), record.decidedAt(), record.decidedBy(),
            record.decisionNote(), Timestamps.nowIso(),
            error == null || error.isEmpty() ? null : Strings.truncate(error, 4000));
        approvals.put(approvalId, updated);
        return updated;
    }

    @Override
    public int deleteForConversation(String sessionId) {
        int removed = 0;
        var iterator = approvals.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().sessionId().equals(sessionId)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }
}
