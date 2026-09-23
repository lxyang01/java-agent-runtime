package io.github.lxyang01.billguard.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.engine.AgentRuntime;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.agent.engine.AgentSpec;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.auth.PermissionDenied;
import io.github.lxyang01.billguard.auth.User;
import io.github.lxyang01.billguard.coordination.RedisLlmLimiter;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgEvidenceStore;
import io.github.lxyang01.billguard.storage.PgTraceReader;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 编排层:chat 全链路、会话锁 423、LLM 槽 429、归属 403、审批恢复、会话删除。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillGuardFacadeTest extends PgTestBase {

    static RedisClient redisClient;
    static RedisCommands<String, String> redis;
    BillGuardFacade facade;
    User alice = new User("alice", "user", false, "2026-01-01");
    User bob = new User("bob", "user", false, "2026-01-01");

    @BeforeAll
    void initFacade() {
        redisClient = RedisClient.create(redisUri());
        redis = redisClient.connect().sync();
        redis.flushdb();
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        BillRepository bills = new BillRepository(jdbc);
        facade = new BillGuardFacade(bills, new BillAnomalies(jdbc, bills),
            new PgConversationStore(jdbc), new PgApprovalStore(jdbc),
            new PgEvidenceStore(jdbc), new PgTraceReader(jdbc), redis,
            new RedisLlmLimiter(redis, 4),
            (user, sessionId) -> scriptedAgent(user.username()),
            Duration.ofSeconds(120),
            sessionId -> jdbc.update("DELETE FROM traces WHERE session_id = ?", sessionId));
    }

    @AfterAll
    void closeRedis() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    /** 每用户一个受控 Agent:先工具调用(bill_overview 演示)再 final。 */
    private AgentRuntime scriptedAgent(String owner) {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.read", "arguments", Map.of())),
            Map.of("final", "分析完成,无未证数字"));
        ToolRegistry tools = new ToolRegistry();
        tools.register(ToolDefinition.of("demo.read", "读", Map.of(
            "type", "object", "properties", Map.of(), "required", List.of()),
            args -> Map.of("count", 3)));
        tools.register(new ToolDefinition("demo.write", "写", Map.of(
            "type", "object", "properties", Map.of("value", Map.of("type", "string")),
            "required", List.of("value"), "additionalProperties", false),
            ToolPolicy.of(RiskLevel.HIGH_WRITE, false, "高风险写"),
            args -> Map.of("written", args.get("value")), null));
        var spec = AgentSpec.builder("demo", "i",
            List.of("demo.read", "demo.write")).build();
        return AgentRuntime.builder(spec, llm, tools,
            new PgConversationStore(jdbc), traceWriter())
            .approvals(new PgApprovalStore(jdbc)).build();
    }

    private io.github.lxyang01.agent.store.TraceWriter traceWriter() {
        return new io.github.lxyang01.billguard.storage.PgTraceWriter(jdbc,
            new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()))));
    }

    @Test
    void chat_full_response_and_session_claim() {
        var result = facade.chat(alice, "s-1", "帮我看看");
        assertThat(result.get("status")).isEqualTo("completed");
        assertThat(result.get("answer")).isEqualTo("分析完成,无未证数字");
        assertThat(result.get("steps")).isEqualTo(2);
        assertThat(result.get("runs")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .isNotEmpty();
        assertThat((List<?>) result.get("evidence")).isEmpty();   // demo.read 非账单工具
        // 会话被 alice 认领
        assertThat(facade.listSessions(alice, "s-1")).isNotEmpty();
        // bob 不可访问 alice 的会话
        assertThatThrownBy(() -> facade.chat(bob, "s-1", "借用"))
            .isInstanceOf(PermissionDenied.class)
            .hasMessage("该分析会话属于其他用户");
    }

    @Test
    void chat_lock_conflict_returns_423_semantics() throws Exception {
        // 先占住 s-lock 的会话锁,再 chat → LockedException
        var lock = io.github.lxyang01.billguard.coordination.RedisSessionLock.acquire(
            redis, "s-lock", Duration.ofSeconds(30)).orElseThrow();
        try {
            assertThatThrownBy(() -> facade.chat(alice, "s-lock", "试试"))
                .isInstanceOf(LockedException.class)
                .hasMessage("另一会话操作正在进行,请稍后重试");
        } finally {
            lock.release();
        }
    }

    @Test
    void llm_slots_exhausted_returns_busy() {
        var limiter = new RedisLlmLimiter(redis, 1, "llm:slots:busy-test");
        redis.del("llm:slots:busy-test");
        assertThat(limiter.acquire()).isTrue();
        // 借助占用全部槽位的 facade:构造一个 limit=1 的实例
        var busyFacade = new BillGuardFacade(null, null, null, null, null, null, redis, limiter,
            (u, s) -> { throw new IllegalStateException("不应到达"); },
            Duration.ofSeconds(120), null);
        assertThatThrownBy(() -> busyFacade.chat(alice, "any", "hi"))
            .isInstanceOf(BusyException.class)
            .hasMessage("服务繁忙,请稍后重试");
        limiter.release();
    }

    @Test
    void decide_requires_capability_and_valid_payload() {
        assertThatThrownBy(() -> facade.decideApproval(
            new User("guest", "user", false, ""), "s-x", Map.of()))
            .hasMessage("approval_id 与 decision(approve 或 reject)不能为空");
    }

    @Test
    void delete_session_removes_all_state() {
        facade.chat(alice, "s-del", "记录一下");
        var result = facade.deleteSession(alice, "s-del");
        assertThat(result.get("deleted")).isEqualTo(true);
        Integer traces = jdbc.queryForObject(
            "SELECT COUNT(*) FROM traces WHERE session_id = ?", Integer.class, "s-del");
        assertThat(traces).isZero();
        Integer evidence = jdbc.queryForObject(
            "SELECT COUNT(*) FROM evidence WHERE session_id = ?", Integer.class, "s-del");
        assertThat(evidence).isZero();
    }

    @Test
    void snapshot_assembles_dashboard() {
        facade.chat(alice, "s-snap", "看看");
        var snapshot = facade.snapshot(alice, "s-snap");
        assertThat(snapshot).containsKeys("session_id", "messages", "sessions", "overview",
            "anomalies", "bills", "categories", "audits", "imports", "subscriptions",
            "reports", "approvals", "runs", "evaluations", "mcp_servers");
        assertThat((List<?>) snapshot.get("messages")).isNotEmpty();
    }
}
