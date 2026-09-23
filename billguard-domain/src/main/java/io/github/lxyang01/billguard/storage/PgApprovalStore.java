package io.github.lxyang01.billguard.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.store.ApprovalRecord;
import io.github.lxyang01.agent.store.ApprovalStore;
import io.github.lxyang01.agent.types.Timestamps;
import io.github.lxyang01.agent.util.Json;
import io.github.lxyang01.agent.util.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PG 审批存储;列名对齐 V001(approvals 表)。
 * decide 的恰好一次语义 = 条件 UPDATE + updateCount 裁决 —— 不得"优化"为先查后写。
 */
public final class PgApprovalStore implements ApprovalStore {

    private static final RowMapper<ApprovalRecord> ROW_MAPPER = (rs, rowNum) ->
        new ApprovalRecord(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("trace_id"),
            rs.getInt("step"),
            rs.getString("tool_name"),
            readMap(rs.getString("arguments")),
            rs.getString("risk_level"),
            rs.getString("policy_reason"),
            rs.getString("status"),
            readMap(rs.getString("checkpoint")),
            rs.getString("requested_at"),
            rs.getString("decided_at"),
            rs.getString("decided_by"),
            rs.getString("decision_note"),
            null,
            rs.getString("execution_error"));

    private final JdbcTemplate jdbc;

    public PgApprovalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ApprovalRecord request(String sessionId, String traceId, int step, String toolName,
                                  Map<String, Object> arguments, ToolPolicy policy,
                                  Map<String, Object> checkpoint) {
        if (policy.riskLevel() == RiskLevel.FORBIDDEN) {
            throw new PolicyException("forbidden tool cannot request approval: " + toolName);
        }
        String approvalId = "POL-" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 12).toUpperCase();
        jdbc.update("""
            INSERT INTO approvals(id, session_id, trace_id, step, tool_name, arguments,
                risk_level, requires_approval, policy_reason, checkpoint, status, requested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
            """,
            approvalId, sessionId, traceId, step, toolName, PgJson.value(arguments),
            policy.riskLevel().wire(), policy.requiresApproval(), policy.reason(),
            PgJson.value(checkpoint), Timestamps.nowIso());
        return get(approvalId);
    }

    @Override
    public ApprovalRecord get(String approvalId) {
        List<ApprovalRecord> found = jdbc.query(
            "SELECT * FROM approvals WHERE id = ?", ROW_MAPPER, approvalId);
        if (found.isEmpty()) {
            throw new PolicyException("approval not found: " + approvalId);
        }
        return found.get(0);
    }

    @Override
    public List<ApprovalRecord> list(String sessionId, List<String> statuses, int limit) {
        if (limit < 1 || limit > 500) {
            throw new PolicyException("approval list limit must be between 1 and 500");
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM approvals");
        List<Object> parameters = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        if (sessionId != null && !sessionId.isEmpty()) {
            clauses.add("session_id = ?");
            parameters.add(sessionId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            clauses.add("status IN ("
                + String.join(",", statuses.stream().map(s -> "?").toList()) + ")");
            parameters.addAll(statuses);
        }
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
        sql.append(" ORDER BY requested_at DESC LIMIT ?");
        parameters.add(limit);
        return jdbc.query(sql.toString(), ROW_MAPPER, parameters.toArray());
    }

    @Override
    public ApprovalRecord decide(String approvalId, boolean approved, String decidedBy,
                                 String note) {
        String decider = decidedBy == null ? "" : decidedBy.strip();
        if (decider.isEmpty()) {
            throw new PolicyException("decided_by is required");
        }
        List<String> status = jdbc.queryForList(
            "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
        if (status.isEmpty()) {
            throw new PolicyException("approval not found: " + approvalId);
        }
        if (!"pending".equals(status.get(0))) {
            throw new PolicyException(
                "approval is already " + status.get(0) + ": " + approvalId);
        }
        // 条件 UPDATE 是并发下的唯一裁决者:恰好一个线程 updateCount=1,其余被拒
        int updated = jdbc.update(
            "UPDATE approvals SET status = ?, decided_at = ?, decided_by = ?, decision_note = ? "
                + "WHERE id = ? AND status = 'pending'",
            approved ? "approved" : "rejected", Timestamps.nowIso(), decider,
            note == null ? "" : Strings.truncate(note.strip(), 1000), approvalId);
        if (updated == 0) {
            throw new PolicyException("approval is already decided: " + approvalId);
        }
        return get(approvalId);
    }

    /**
     * 进入执行中(approved → executing,条件 UPDATE):标记工具即将执行。
     * 审计语义:审批决定恰好一次;executing 状态把「执行后崩溃重放」的窗口从
     * [执行前, markExecution 后] 收窄为 [markExecuting 后],恢复侧遇到 executing
     * 拒绝自动重放(工具副作用幂等由下游保证,如工单域的 idempotent_replay)。
     */
    @Override
    public ApprovalRecord markExecuting(String approvalId) {
        int updated = jdbc.update(
            "UPDATE approvals SET status = 'executing' WHERE id = ? AND status = 'approved'",
            approvalId);
        if (updated == 0) {
            List<String> status = jdbc.queryForList(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
            throw new PolicyException("approval cannot enter executing from "
                + (status.isEmpty() ? "missing" : status.get(0)) + ": " + approvalId);
        }
        return get(approvalId);
    }

    /** 运维/超时回收:executing 滚回 approved(仅当确认无副作用或下游幂等)。 */
    public ApprovalRecord reclaimExecuting(String approvalId) {
        int updated = jdbc.update(
            "UPDATE approvals SET status = 'approved' WHERE id = ? AND status = 'executing'",
            approvalId);
        if (updated == 0) {
            throw new PolicyException("approval is not executing: " + approvalId);
        }
        return get(approvalId);
    }

    @Override
    public ApprovalRecord markExecution(String approvalId, boolean succeeded, String error) {
        List<String> status = jdbc.queryForList(
            "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
        if (status.isEmpty()) {
            throw new PolicyException("approval not found: " + approvalId);
        }
        if (!List.of("approved", "executing").contains(status.get(0))) {
            throw new PolicyException(
                "only approved/executing requests can be executed: " + status.get(0));
        }
        Map<String, Object> executionResult = new LinkedHashMap<>();
        executionResult.put("succeeded", succeeded);
        executionResult.put("executed_at", Timestamps.nowIso());
        jdbc.update(
            "UPDATE approvals SET status = ?, execution_result = ?, execution_error = ? "
                + "WHERE id = ?",
            succeeded ? "executed" : "failed", PgJson.value(executionResult),
            error == null || error.isEmpty() ? null : Strings.truncate(error, 4000),
            approvalId);
        return get(approvalId);
    }

    @Override
    public int deleteForConversation(String sessionId) {
        return jdbc.update("DELETE FROM approvals WHERE session_id = ?", sessionId);
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null) {
            return Map.of();
        }
        return Json.MAPPER.convertValue(Json.readTree(json),
            new TypeReference<LinkedHashMap<String, Object>>() {});
    }
}
