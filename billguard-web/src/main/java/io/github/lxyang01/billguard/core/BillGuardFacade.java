package io.github.lxyang01.billguard.core;

import io.github.lxyang01.agent.engine.AgentRuntime;
import io.github.lxyang01.agent.guardrail.Guardrails;
import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.types.AgentResponse;
import io.github.lxyang01.agent.types.ChatMessage;
import io.github.lxyang01.agent.types.Conversation;
import io.github.lxyang01.agent.types.RunEvent;
import io.github.lxyang01.billguard.auth.Capabilities;
import io.github.lxyang01.billguard.auth.PermissionDenied;
import io.github.lxyang01.billguard.auth.User;
import io.github.lxyang01.billguard.bills.BillFilters;
import io.github.lxyang01.billguard.chat.EvidenceBuilder;
import io.github.lxyang01.billguard.coordination.RedisLlmLimiter;
import io.github.lxyang01.billguard.coordination.RedisSessionLock;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgEvidenceStore;
import io.github.lxyang01.billguard.storage.PgTraceReader;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 应用编排层(对齐 web.py BillGuardApp):
 * chat = LLM 槽(429)→ 会话锁(423)→ 归属检查/认领 → 引擎 run → 证据落库 → 响应组装;
 * decide 与 chat 共用同一把会话锁(审批恢复与对话都会回写 Session,无锁会互相覆盖)。
 */
public final class BillGuardFacade {

    /** 按用户/会话装配 Agent(生产:BillAgentFactory;测试:ScriptedLlm)。 */
    public interface AgentProvider extends BiFunction<User, String, AgentRuntime> {}

    private final BillRepository bills;
    private final BillAnomalies anomalies;
    private final PgConversationStore conversations;
    private final PgApprovalStore approvals;
    private final PgEvidenceStore evidenceStore;
    private final PgTraceReader traceReader;
    private final RedisCommands<String, String> redis;
    private final RedisLlmLimiter llmSlots;
    private final AgentProvider agentProvider;
    private final Duration runTimeout;
    private final java.util.function.Consumer<String> traceDeleter;
    private io.github.lxyang01.billguard.metrics.AppMetrics metrics;   // 可空:测试直构不埋点
    private io.github.lxyang01.billguard.storage.PgWorkItemStore workItems;        // MCP 模式双闸
    private java.util.function.Supplier<List<Map<String, Object>>> mcpServersView; // 快照列服务

    public BillGuardFacade(BillRepository bills, BillAnomalies anomalies,
                           PgConversationStore conversations, PgApprovalStore approvals,
                           PgEvidenceStore evidenceStore, PgTraceReader traceReader,
                           RedisCommands<String, String> redis, RedisLlmLimiter llmSlots,
                           AgentProvider agentProvider, Duration runTimeout,
                           java.util.function.Consumer<String> traceDeleter) {
        this.bills = bills;
        this.anomalies = anomalies;
        this.conversations = conversations;
        this.approvals = approvals;
        this.evidenceStore = evidenceStore;
        this.traceReader = traceReader;
        this.redis = redis;
        this.llmSlots = llmSlots;
        this.agentProvider = agentProvider;
        this.runTimeout = runTimeout;
        this.traceDeleter = traceDeleter;
    }

    public void setMetrics(io.github.lxyang01.billguard.metrics.AppMetrics metrics) {
        this.metrics = metrics;
    }

    /** MCP 模式装配:工单存储(commit_issue 双闸)+ mcp_servers 视图提供者。 */
    public void setMcpMode(io.github.lxyang01.billguard.storage.PgWorkItemStore workItems,
                           java.util.function.Supplier<List<Map<String, Object>>> mcpServersView) {
        this.workItems = workItems;
        this.mcpServersView = mcpServersView;
    }

    private List<Map<String, Object>> mcpServers() {
        return mcpServersView == null ? List.of() : mcpServersView.get();
    }

    private void inc(String name) {
        if (metrics != null) {
            metrics.inc(name);
        }
    }

    /** 证据来自持久化 trace 的 tool_end 事件(与 /api/runs 同一数据源,单一事实)。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evidenceFromTrace(String sessionId, String traceId) {
        try {
            Map<String, Object> run = traceReader.getRun(sessionId, traceId);
            List<RunEvent> events = new ArrayList<>();
            for (Object eventObj : (List<?>) run.get("events")) {
                Map<String, Object> raw = (Map<String, Object>) eventObj;
                events.add(new RunEvent(String.valueOf(raw.get("event")), traceId, sessionId,
                    raw.get("step") instanceof Number n ? n.intValue() : 0, raw,
                    String.valueOf(raw.getOrDefault("timestamp", ""))));
            }
            return EvidenceBuilder.build(events);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ---- 会话归属 ----

    private void requireSessionAccess(User user, String sessionId) {
        if (!conversations.exists(sessionId)) {
            return;
        }
        String owner = conversations.load(sessionId).getOwner();
        if (owner == null) {
            if (!user.isAdmin()) {
                throw new PermissionDenied("该分析会话未分配归属,仅管理员可访问");
            }
            return;
        }
        if (!owner.equals(user.username())) {
            throw new PermissionDenied("该分析会话属于其他用户");
        }
    }

    private void claimSession(User user, String sessionId) {
        Conversation conversation = conversations.load(sessionId);
        if (conversation.getOwner() == null) {
            conversation.setOwner(user.username());
            conversations.save(conversation);
        }
    }

    /** 会话锁:Redis 分布式(TTL=run_timeout+60s),被占用立即 423 不排队。 */
    private RedisSessionLock sessionGuard(String sessionId) {
        RedisSessionLock lock = RedisSessionLock.acquire(redis, sessionId,
            runTimeout.plusSeconds(60)).orElse(null);
        if (lock == null) {
            inc("lock_conflicts_total");
            throw new LockedException("另一会话操作正在进行,请稍后重试");
        }
        return lock;
    }

    // ---- chat / decide ----

    public Map<String, Object> chat(User user, String sessionId, String message) {
        Guardrails.validateUserInput(message);
        if (!llmSlots.acquire()) {
            throw new BusyException("服务繁忙,请稍后重试");
        }
        try {
            return chatLocked(user, sessionId, message);
        } finally {
            llmSlots.release();
        }
    }

    private Map<String, Object> chatLocked(User user, String sessionId, String message) {
        RedisSessionLock lock = sessionGuard(sessionId);
        try {
            requireSessionAccess(user, sessionId);
            claimSession(user, sessionId);
            AgentRuntime agent = agentProvider.apply(user, sessionId);
            AgentResponse response = agent.run(sessionId, message);
            List<Map<String, Object>> evidence = evidenceFromTrace(sessionId, response.traceId());
            evidenceStore.save(sessionId, response.answer(), evidence);
            return chatResponse(user, sessionId, response, evidence);
        } finally {
            lock.release();
        }
    }

    private Map<String, Object> chatResponse(User user, String sessionId,
                                             AgentResponse response,
                                             List<Map<String, Object>> evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", response.answer());
        result.put("steps", response.steps());
        result.put("trace_id", response.traceId());
        result.put("active_skills", response.activeSkills());
        result.put("sessions", listSessions(user, sessionId));
        result.put("overview", bills.overview(BillFilters.EMPTY, user.username()));
        result.put("evidence", evidence);
        result.put("mcp_servers", mcpServers());
        result.put("status", response.status());
        result.put("approval", response.approval() == null ? null
            : enrichApproval(new LinkedHashMap<>(response.approval())));
        result.put("approvals", approvals(user, sessionId));
        result.put("runs", traceReader.listRuns(sessionId, 50));
        return result;
    }

    public Map<String, Object> decideApproval(User user, String sessionId,
                                              Map<String, Object> body) {
        if (!Capabilities.can(user.role(), Capabilities.APPROVAL_DECIDE)) {
            throw new PermissionDenied("当前角色无审批权限");
        }
        requireSessionAccess(user, sessionId);
        String approvalId = text(body.get("approval_id"));
        String decision = text(body.get("decision")).toLowerCase();
        if (approvalId.isEmpty() || !List.of("approve", "reject").contains(decision)) {
            throw new PolicyException("approval_id 与 decision(approve 或 reject)不能为空");
        }
        if (!llmSlots.acquire()) {
            throw new BusyException("服务繁忙,请稍后重试");
        }
        try {
            Map<String, Object> decided = decideLocked(user, sessionId, approvalId,
                "approve".equals(decision), text(body.get("note")));
            inc("approvals_decided_total");
            return decided;
        } finally {
            llmSlots.release();
        }
    }

    private Map<String, Object> decideLocked(User user, String sessionId, String approvalId,
                                             boolean approved, String note) {
        // 与 chat 共用同一把会话锁:审批恢复与进行中的对话都会回写 Session 存储
        RedisSessionLock lock = sessionGuard(sessionId);
        try {
            var current = approvals.get(approvalId);
            if (!sessionId.equals(current.sessionId())) {
                throw new PolicyException("该审批不属于当前会话");
            }
            // 双闸:commit 类审批先过工单域决定(伪造 checkpoint 也过不了第二道闸)
            if (workItems != null && current.toolName().endsWith("commit_issue")) {
                Object remoteId = current.arguments().get("approval_id");
                String remoteApprovalId = remoteId == null
                    ? "" : String.valueOf(remoteId).strip();
                if (remoteApprovalId.isEmpty()) {
                    throw new PolicyException("commit_issue 审批缺少远程 approval_id");
                }
                workItems.decide(remoteApprovalId, approved, user.username());
            }
            approvals.decide(approvalId, approved, user.username(), note);
            AgentRuntime agent = agentProvider.apply(user, sessionId);
            AgentResponse response = approved
                ? agent.resume(approvalId) : agent.finalizeRejection(approvalId);
            List<Map<String, Object>> evidence = evidenceFromTrace(sessionId,
                response.traceId());
            evidenceStore.save(sessionId, response.answer(), evidence);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", response.answer());
            result.put("steps", response.steps());
            result.put("trace_id", response.traceId());
            result.put("status", response.status());
            result.put("approval", approvals.get(approvalId).asMap());
            result.put("approvals", approvals(user, sessionId));
            result.put("sessions", listSessions(user, sessionId));
            result.put("overview", bills.overview(BillFilters.EMPTY, user.username()));
            result.put("evidence", evidence);
            result.put("mcp_servers", mcpServers());
            result.put("runs", traceReader.listRuns(sessionId, 50));
            return result;
        } finally {
            lock.release();
        }
    }

    // ---- 快照与列表 ----

    public Map<String, Object> snapshot(User user, String sessionId) {
        requireSessionAccess(user, sessionId);
        Conversation conversation = conversations.load(sessionId);
        Map<String, String> answerHashes = new LinkedHashMap<>();
        for (ChatMessage message : conversation.messages()) {
            if ("assistant".equals(message.role().wire()) && message.toolCallId() == null) {
                answerHashes.put(message.content(),
                    PgEvidenceStore.answerKey(message.content()));
            }
        }
        Map<String, List<Map<String, Object>>> storedEvidence = evidenceStore.load(sessionId);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage message : conversation.messages()) {
            String role = message.role().wire();
            if ((!role.equals("user") && !role.equals("assistant")) || message.toolCallId() != null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(message.asMap());
            if (role.equals("assistant")) {
                item.put("evidence", storedEvidence.getOrDefault(
                    PgEvidenceStore.answerKey(message.content()), List.of()));
            }
            messages.add(item);
        }
        String owner = user.username();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("messages", messages);
        result.put("summary", conversation.getSummary());
        result.put("sessions", listSessions(user, sessionId));
        result.put("overview", bills.overview(BillFilters.EMPTY, owner));
        result.put("anomalies", anomalies.anomalies(31, "spike", 8, owner));
        result.put("bills", bills.query(BillFilters.EMPTY, 1, 30, owner));
        result.put("categories", bills.categories(owner));
        result.put("audits", bills.recentAudits(50, owner));
        result.put("imports", bills.imports(20, owner));
        result.put("subscriptions", bills.subscriptions(owner));
        result.put("reports", bills.reports(50, owner));
        result.put("mcp_servers", mcpServers());
        result.put("approvals", approvals(user, sessionId));
        result.put("runs", traceReader.listRuns(sessionId, 50));
        result.put("evaluations", List.of());
        return result;
    }

    public List<Map<String, Object>> approvals(User user, String sessionId) {
        requireSessionAccess(user, sessionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var record : approvals.list(sessionId, null, 100)) {
            result.add(enrichApproval(record.asMap()));
        }
        return result;
    }

    /** commit 类审批补全人可读的工单内容(action_title/description/priority)。 */
    private Map<String, Object> enrichApproval(Map<String, Object> data) {
        if (workItems == null || !String.valueOf(data.get("tool_name")).endsWith("commit_issue")) {
            return data;
        }
        Object remoteId = ((Map<?, ?>) data.get("arguments")).get("approval_id");
        if (remoteId == null || String.valueOf(remoteId).isBlank()) {
            return data;
        }
        try {
            Map<?, ?> payload = (Map<?, ?>) workItems.approval(String.valueOf(remoteId))
                .get("payload");
            data.putIfAbsent("action_title", payload.get("title"));
            data.putIfAbsent("action_description", payload.get("description"));
            String priority = String.valueOf(payload.get("priority"));
            data.putIfAbsent("action_priority", switch (priority) {
                case "high" -> "高";
                case "medium" -> "中";
                case "low" -> "低";
                default -> priority;
            });
        } catch (RuntimeException ignored) {
            // 补全是展示增强:工单缺失时返回原始卡片
        }
        return data;
    }

    /** 可恢复会话列表(按 updated_at 倒序,最多 20;活跃会话置顶)。 */
    public List<Map<String, Object>> listSessions(User user, String activeSessionId) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (var summary : conversations.list()) {
            String owner = summary.owner();
            boolean visible = owner != null && owner.equals(user.username())
                || (owner == null && user.isAdmin());
            if (!visible) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", summary.sessionId());
            item.put("message_count", summary.messages().stream().filter(
                m -> List.of("user", "assistant").contains(m.role().wire())
                    && m.toolCallId() == null).count());
            item.put("updated_at", epochSeconds(summary.updatedAt()));
            sessions.add(item);
        }
        String active = activeSessionId == null ? "" : activeSessionId;
        if (!active.isEmpty() && sessions.stream()
            .noneMatch(item -> active.equals(item.get("id")))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", active);
            item.put("message_count", 0);
            item.put("updated_at", 0.0);
            sessions.add(item);
        }
        sessions.sort((a, b) -> {
            boolean aActive = a.get("id").equals(active);
            boolean bActive = b.get("id").equals(active);
            if (aActive != bActive) {
                return aActive ? -1 : 1;
            }
            return Double.compare(((Number) b.get("updated_at")).doubleValue(),
                ((Number) a.get("updated_at")).doubleValue());
        });
        return sessions.subList(0, Math.min(20, sessions.size()));
    }

    public Map<String, Object> listRuns(User user, String sessionId, int limit) {
        requireSessionAccess(user, sessionId);
        return Map.of("runs", traceReader.listRuns(sessionId, limit));
    }

    public Map<String, Object> runDetail(User user, String sessionId, String traceId) {
        requireSessionAccess(user, sessionId);
        return traceReader.getRun(sessionId, traceId);
    }

    /** 删除一个会话的全部持久化状态(锁内删,防与 chat/decide 并发删写)。 */
    public Map<String, Object> deleteSession(User user, String sessionId) {
        requireSessionAccess(user, sessionId);
        RedisSessionLock lock = sessionGuard(sessionId);
        boolean deleted;
        try {
            deleted = conversations.delete(sessionId) > 0;
            if (traceDeleter != null) {
                traceDeleter.accept(sessionId);
            }
            evidenceStore.delete(sessionId);
        } finally {
            lock.release();
        }
        approvals.deleteForConversation(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("session_id", sessionId);
        result.put("sessions", listSessions(user, ""));
        return result;
    }


    private static double epochSeconds(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli() / 1000.0;
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
