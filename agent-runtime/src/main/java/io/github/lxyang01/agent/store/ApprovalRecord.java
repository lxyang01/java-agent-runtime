package io.github.lxyang01.agent.store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批记录(Harness 级三阶段状态机:pending → approved/rejected → executed/failed)。
 * asMap 字段名与 ApprovalRequest.as_dict 一致(API 契约)。
 */
public record ApprovalRecord(
    String id,
    String sessionId,
    String traceId,
    int step,
    String toolName,
    Map<String, Object> arguments,
    String riskLevel,
    String reason,
    String status,
    Map<String, Object> checkpoint,
    String requestedAt,
    String decidedAt,
    String decidedBy,
    String decisionNote,
    String executedAt,
    String executionError) {

    public Map<String, Object> asMap() {
        return asMap(false);
    }

    public Map<String, Object> asMap(boolean includeCheckpoint) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("session_id", sessionId);
        value.put("trace_id", traceId);
        value.put("step", step);
        value.put("tool_name", toolName);
        value.put("arguments", arguments);
        value.put("risk_level", riskLevel);
        value.put("reason", reason);
        value.put("status", status);
        value.put("requested_at", requestedAt);
        value.put("decided_at", decidedAt);
        value.put("decided_by", decidedBy);
        value.put("decision_note", decisionNote);
        value.put("executed_at", executedAt);
        value.put("execution_error", executionError);
        if (includeCheckpoint) {
            value.put("checkpoint", checkpoint);
        }
        return value;
    }
}
