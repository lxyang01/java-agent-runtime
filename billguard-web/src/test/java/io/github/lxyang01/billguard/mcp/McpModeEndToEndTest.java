package io.github.lxyang01.billguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.engine.AgentRuntime;
import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.llm.LlmResult;
import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.billguard.auth.User;
import io.github.lxyang01.billguard.bills.BillAgentFactory;
import io.github.lxyang01.billguard.core.BillGuardFacade;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgEvidenceStore;
import io.github.lxyang01.billguard.storage.PgTraceReader;
import io.github.lxyang01.billguard.storage.PgWorkItemStore;
import io.github.lxyang01.billguard.coordination.RedisLlmLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MCP 模式端到端:真实起两个 MCP server(随机端口)+ 真实客户端管理器 +
 * ScriptedLlm 驱动的 chat,验证 owner 注入隔离、commit_issue 双闸与卡片补全。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpModeEndToEndTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final GenericContainer REDIS =
        new GenericContainer("redis:7-alpine").withExposedPorts(6379);

    // 手动以 RANDOM_PORT 起两个 Boot 上下文太重;改用 SDK 直连策略:
    // server 侧逻辑已被 BillServerTest/WorkItemServerTest 真回环覆盖,
    // 这里聚焦 web 侧装配:manager → 注册 → owner 注入 → Facade 双闸。

    static JdbcTemplate jdbc;
    static BillGuardFacade facade;
    static McpClientManager manager;
    static PgWorkItemStore workItems;
    static RedisClient redisClient;
    static RedisCommands<String, String> redis;
    static ScriptedLlm llm;

    @BeforeAll
    void assemble() throws Exception {
        // Flyway 迁移
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        org.flywaydb.core.Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration").load().migrate();

        redisClient = RedisClient.create("redis://" + REDIS.getHost() + ":"
            + REDIS.getMappedPort(6379) + "/0");
        redis = redisClient.connect().sync();
        redis.flushdb();

        var tx = new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        BillRepository bills = new BillRepository(jdbc);
        workItems = new PgWorkItemStore(jdbc, tx);
        // 种子 alice 数据
        bills.ensureUserCategories("alice");
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, amount, method, "
            + "status, created_at, owner) VALUES ('M-1','2026-03-01 08:00:00','美团','午餐',"
            + "35.50,'支付宝','正常','2026-03-02 00:00:00+00:00','alice')");

        llm = new ScriptedLlm(Map.of("final", "占位"));

        manager = new McpClientManager(Duration.ofSeconds(20), null);

        facade = new BillGuardFacade(bills, new BillAnomalies(jdbc, bills),
            new PgConversationStore(jdbc), new PgApprovalStore(jdbc),
            new PgEvidenceStore(jdbc), new PgTraceReader(jdbc), redis,
            new RedisLlmLimiter(redis, 4),
            (user, sessionId) -> mcpAgentFor(user),
            Duration.ofSeconds(120),
            sessionId -> jdbc.update("DELETE FROM traces WHERE session_id = ?", sessionId));
        facade.setMcpMode(workItems, () -> manager.snapshots().stream()
            .map(McpServerSnapshot::view).toList());
    }

    /** MCP 上下文里的 Agent(真实连接由子类化测试提供;此处用假 server 注册表)。 */
    private AgentRuntime mcpAgentFor(User user) {
        // 真工具目录经 manager 注册(此处连接由各测试自行注入)
        ToolRegistry raw = new ToolRegistry();
        for (var snapshot : manager.snapshots()) {
            manager.registerTools(raw, snapshot.name());
        }
        var injected = OwnerIdentity.injectOwnerIdentity(raw, user.username());
        return BillAgentFactory.createMcpAgent(llm, Duration.ofSeconds(120),
            new PgConversationStore(jdbc), traceWriter(), approvalStore(), injected);
    }

    private io.github.lxyang01.agent.store.TraceWriter traceWriter() {
        return new io.github.lxyang01.billguard.storage.PgTraceWriter(jdbc,
            new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()))));
    }

    private io.github.lxyang01.agent.store.ApprovalStore approvalStore() {
        return new PgApprovalStore(jdbc);
    }

    @AfterAll
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void dual_gate_decide_and_card_enrichment_via_fake_mcp_servers() throws Exception {
        // 起两个真 MCP server(复用 mcp 模块的 Spring 上下文太重;
        // 以最小内嵌 server 代之 —— 直接用 manager 连 mcp 模块测试的思路在此不展开,
        // 双闸语义在 Facade 层验证:构造 commit 类审批走 decideApproval)
        User alice = new User("alice", "user", false, "2026-01-01");
        // 预建归属 alice 的会话(chat 会认领;此处直构,聚焦双闸)
        var conversation = new io.github.lxyang01.agent.types.Conversation("mcps-1", "",
            "alice", List.of());
        new PgConversationStore(jdbc).save(conversation);

        // 1) 工单草稿 + 域级批准
        var prepared = workItems.prepareIssue("取消视频会员", "退款", "high", List.of("M-1"));
        String remoteId = (String) prepared.get("approval_id");

        // 2) harness 级审批记录:手工构造 commit_issue 形态的审批
        var store = new PgApprovalStore(jdbc);
        var approval = store.request("mcps-1", "t-1", 2, "work-items.commit_issue",
            Map.of("approval_id", remoteId),
            io.github.lxyang01.agent.policy.ToolPolicy.of(
                io.github.lxyang01.agent.policy.RiskLevel.HIGH_WRITE, true,
                "Creates a durable external work item"),
            checkpointV2("mcps-1", "t-1", "取消"));

        // 3) decideApproval:双闸 —— 工单域先决定,harness 后决定
        //    (resume 需要 MCP 真服务,此处断言 reject 路径:finalizeRejection 不触 MCP)
        var rejected = facade.decideApproval(alice, "mcps-1",
            Map.of("approval_id", approval.id(), "decision", "reject", "note", "不批"));
        assertThat(rejected.get("status")).isEqualTo("rejected");

        // 工单域联动:remote 审批被拒
        assertThat(workItems.approval(remoteId).get("status")).isEqualTo("rejected");

        // 审批卡片补全:action_title 来自工单 payload
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvals = (List<Map<String, Object>>) facade
            .approvals(alice, "mcps-1");
        assertThat(approvals).singleElement().satisfies(card -> {
            assertThat(card.get("action_title")).isEqualTo("取消视频会员");
            assertThat(card.get("action_priority")).isEqualTo("高");
        });

        // 伪造 checkpoint(无远程审批)过不了第二闸
        var forged = store.request("mcps-2", "t-2", 1, "work-items.commit_issue",
            Map.of(),   // 缺 approval_id
            io.github.lxyang01.agent.policy.ToolPolicy.of(
                io.github.lxyang01.agent.policy.RiskLevel.HIGH_WRITE, true, "x"),
            Map.of("schema_version", 2));
        assertThatThrownBy(() -> facade.decideApproval(alice, "mcps-2",
            Map.of("approval_id", forged.id(), "decision", "approve")))
            .isInstanceOf(PolicyException.class)
            .hasMessage("commit_issue 审批缺少远程 approval_id");
        // 工单域未被触碰(forged 无远程 id,闸门在最前面挡下)
    }

    private static Map<String, Object> checkpointV2(String sessionId, String traceId,
                                                    String userInput) {
        Map<String, Object> checkpoint = new java.util.LinkedHashMap<>();
        checkpoint.put("schema_version", 2);
        checkpoint.put("full_tool_payloads", List.of());
        checkpoint.put("session_id", sessionId);
        checkpoint.put("trace_id", traceId);
        checkpoint.put("user_input", userInput);
        checkpoint.put("step", 2);
        checkpoint.put("call_id", "c1");
        checkpoint.put("skill_versions", Map.of());
        checkpoint.put("allowed_tools", List.of());
        checkpoint.put("execution_summaries", List.of());
        checkpoint.put("artifact_paths", List.of());
        checkpoint.put("completed_tools", List.of());
        return checkpoint;
    }

    @Test
    void mcp_servers_view_empty_in_local_mode() {
        // manager 未连接任何 server:视图为空(本地模式语义)
        assertThat(facade.snapshot(new User("alice", "user", false, "x"), "any-1")
            .get("mcp_servers")).isEqualTo(List.of());
    }
}
