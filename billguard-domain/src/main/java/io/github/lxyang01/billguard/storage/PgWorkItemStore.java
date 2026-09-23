package io.github.lxyang01.billguard.storage;

import io.github.lxyang01.billguard.workitems.WorkItemException;
import io.github.lxyang01.billguard.workitems.WorkItemTexts;
import io.github.lxyang01.agent.types.Timestamps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工单 PG 存储(wi_approvals + issues)。SQL 对齐 storage_pg.PGWorkItemStore:
 * decide 条件 UPDATE 恰好一次(过期置 expired);commit 在 FOR UPDATE 事务内
 * 幂等(consumed+issue_id 已存在 → idempotent_replay);issues 主键 BIGSERIAL,
 * 对外呈现 ISS-NNNN;工单 status/evidence_refs 记入 payload JSONB(schema 无列)。
 */
public final class PgWorkItemStore {

    private static final Set<String> PRIORITIES = Set.of("low", "medium", "high", "urgent");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PgWorkItemStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Map<String, Object> prepareIssue(String title, String description, String priority,
                                            List<String> evidenceRefs) {
        String safeTitle = title == null ? "" : title.strip();
        String safeDescription = description == null ? "" : description.strip();
        if (safeTitle.isEmpty() || safeTitle.length() > 200) {
            throw new WorkItemException("title must contain between 1 and 200 characters");
        }
        if (safeDescription.isEmpty() || safeDescription.length() > 5_000) {
            throw new WorkItemException("description must contain between 1 and 5000 characters");
        }
        if (!PRIORITIES.contains(priority)) {
            throw new WorkItemException("priority must be low, medium, high, or urgent");
        }
        List<String> refs = evidenceRefs == null ? List.of()
            : evidenceRefs.stream().map(ref -> ref == null ? "" : ref.strip())
                .filter(ref -> !ref.isEmpty()).distinct().toList();
        if (refs.size() > 50) {
            throw new WorkItemException("evidence_refs cannot exceed 50 items");
        }
        String approvalId = "APR-" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 10).toUpperCase();
        String now = Timestamps.nowIso();
        String expiresAt = OffsetDateTime.parse(now).plus(30, ChronoUnit.MINUTES).toString()
            .replaceFirst("\\+", "\\+");   // 保持与存量一致的 ISO 形态
        String nextStep = WorkItemTexts.prepareIssueNextStep(approvalId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", safeTitle);
        payload.put("description", safeDescription);
        payload.put("priority", priority);
        payload.put("evidence_refs", refs);
        jdbc.update("INSERT INTO wi_approvals(id, action, payload, status, requested_at, "
                + "expires_at, next_step) VALUES (?, 'issue.create', ?::jsonb, 'pending', ?, ?, ?)",
            approvalId, PgJson.value(payload), now, expiresAt, nextStep);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approval_id", approvalId);
        result.put("status", "pending");
        result.put("action", "issue.create");
        result.put("payload", payload);
        result.put("expires_at", expiresAt);
        result.put("next_step", nextStep);
        return result;
    }

    public Map<String, Object> decide(String approvalId, boolean approved, String decidedBy) {
        String decider = decidedBy == null ? "" : decidedBy.strip();
        if (decider.isEmpty()) {
            throw new WorkItemException("decided_by is required");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM wi_approvals WHERE id = ?", approvalId);
        if (rows.isEmpty()) {
            throw new WorkItemException("approval not found: " + approvalId);
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if (!"pending".equals(status)) {
            throw new WorkItemException("approval is already " + status + ": " + approvalId);
        }
        if (OffsetDateTime.parse(String.valueOf(rows.get(0).get("expires_at")))
            .isBefore(OffsetDateTime.now())) {
            jdbc.update("UPDATE wi_approvals SET status = 'expired' WHERE id = ?", approvalId);
            throw new WorkItemException("approval has expired: " + approvalId);
        }
        // 条件 UPDATE 保证并发 decide 恰好一个生效(与 PgApprovalStore.decide 同款)
        int updated = jdbc.update("UPDATE wi_approvals SET status = ?, decided_by = ?, "
                + "decided_at = ? WHERE id = ? AND status = 'pending'",
            approved ? "approved" : "rejected", decider, Timestamps.nowIso(), approvalId);
        if (updated == 0) {
            throw new WorkItemException("approval is already decided: " + approvalId);
        }
        return approval(approvalId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> commitIssue(String approvalId) {
        return tx.execute(status -> {
            // FOR UPDATE 行锁等价单进程 BEGIN IMMEDIATE:并发 commit 只建一单
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM wi_approvals WHERE id = ? FOR UPDATE", approvalId);
            if (rows.isEmpty()) {
                throw new WorkItemException("approval not found: " + approvalId);
            }
            Map<String, Object> row = rows.get(0);
            Number issueId = (Number) row.get("issue_id");
            if ("consumed".equals(row.get("status")) && issueId != null) {
                Map<String, Object> issue = issueRow(issueId.longValue());
                Map<String, Object> replay = new LinkedHashMap<>();
                replay.put("created", issue);
                replay.put("idempotent_replay", true);
                return replay;
            }
            if (!"approved".equals(row.get("status"))) {
                throw new WorkItemException(
                    "approval must be approved before commit: " + row.get("status"));
            }
            Map<String, Object> payload = readJson(row.get("payload"));
            Map<String, Object> issuePayload = new LinkedHashMap<>();
            issuePayload.put("status", "open");
            Object refs = payload.get("evidence_refs");
            issuePayload.put("evidence_refs", refs == null ? List.of() : refs);
            var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                    "INSERT INTO issues(title, description, priority, created_by, approval_id, "
                        + "created_at, payload) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, String.valueOf(payload.get("title")));
                ps.setString(2, String.valueOf(payload.get("description")));
                ps.setString(3, String.valueOf(payload.get("priority")));
                ps.setString(4, String.valueOf(row.get("decided_by")));
                ps.setString(5, approvalId);
                ps.setString(6, Timestamps.nowIso());
                ps.setString(7, io.github.lxyang01.agent.util.Json.write(issuePayload));
                return ps;
            }, keyHolder);
            long numericId = ((Number) keyHolder.getKeys().get("id")).longValue();
            jdbc.update("UPDATE wi_approvals SET status = 'consumed', issue_id = ? WHERE id = ?",
                numericId, approvalId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("created", issueRow(numericId));
            result.put("idempotent_replay", false);
            return result;
        });
    }

    public Map<String, Object> listIssues(String status, int limit) {
        if (limit < 1 || limit > 200) {
            throw new WorkItemException("limit must be between 1 and 200");
        }
        List<Object> args = new ArrayList<>();
        String sql = "SELECT * FROM issues";
        if (status != null && !status.isEmpty()) {
            sql += " WHERE payload ->> 'status' = ?";
            args.add(status);
        }
        sql += " ORDER BY created_at DESC LIMIT ?";
        args.add(limit);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(issueView(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("count", items.size());
        return result;
    }

    public Map<String, Object> getIssue(String issueId) {
        long numeric = issueNumber(issueId);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM issues WHERE id = ?", numeric);
        if (rows.isEmpty()) {
            throw new WorkItemException("issue not found: " + issueId);
        }
        return issueView(rows.get(0));
    }

    public Map<String, Object> approval(String approvalId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM wi_approvals WHERE id = ?", approvalId);
        if (rows.isEmpty()) {
            throw new WorkItemException("approval not found: " + approvalId);
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approval_id", row.get("id"));
        result.put("action", row.get("action"));
        result.put("payload", readJson(row.get("payload")));
        result.put("status", row.get("status"));
        result.put("requested_at", row.get("requested_at"));
        result.put("expires_at", row.get("expires_at"));
        result.put("decided_by", row.get("decided_by"));
        result.put("decided_at", row.get("decided_at"));
        result.put("issue_id", row.get("issue_id") == null ? null
            : issueLabel(((Number) row.get("issue_id")).longValue()));
        return result;
    }

    public List<Map<String, Object>> pendingApprovals() {
        List<String> ids = jdbc.queryForList(
            "SELECT id FROM wi_approvals WHERE status = 'pending' ORDER BY requested_at",
            String.class);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            result.add(approval(id));
        }
        return result;
    }

    // ---- 内部 ----

    private Map<String, Object> issueRow(long numericId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM issues WHERE id = ?", numericId);
        if (rows.isEmpty()) {
            throw new WorkItemException("issue disappeared during transaction");
        }
        return issueView(rows.get(0));
    }

    private static Map<String, Object> issueView(Map<String, Object> row) {
        Map<String, Object> payload = readJson(row.get("payload"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", issueLabel(((Number) row.get("id")).longValue()));
        view.put("title", row.get("title"));
        view.put("description", row.get("description"));
        view.put("priority", row.get("priority"));
        view.put("status", payload.getOrDefault("status", "open"));
        view.put("evidence_refs", payload.getOrDefault("evidence_refs", List.of()));
        view.put("created_by", row.get("created_by"));
        view.put("created_at", row.get("created_at"));
        return view;
    }

    private static String issueLabel(long numericId) {
        return String.format("ISS-%04d", numericId);
    }

    private static long issueNumber(String issueId) {
        String text = issueId == null ? "" : issueId.strip();
        if (text.toUpperCase().startsWith("ISS-")) {
            text = text.substring(4);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new WorkItemException("issue not found: " + issueId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        try {
            return io.github.lxyang01.agent.util.Json.MAPPER.readValue(String.valueOf(value),
                new com.fasterxml.jackson.core.type.TypeReference<
                    LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
