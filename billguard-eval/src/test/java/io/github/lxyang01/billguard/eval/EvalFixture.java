package io.github.lxyang01.billguard.eval;

import io.github.lxyang01.agent.engine.AgentRuntime;
import io.github.lxyang01.agent.engine.AgentSpec;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgTraceWriter;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.agent.types.RunEvent;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 评测夹具:共享 PG/Redis 容器;每探针前 resetFixtures(12 表 + 夹具用户
 * alice 与 mallory + 会话锁和槽位键清扫)。探针共用 Engine 装配
 * 与 ScriptedLlm(即 Python QueueLLM)。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class EvalFixture extends PgTestBase {

    public static final String[] FIXTURE_TABLES = {
        "tx_audits", "transactions", "categories", "subscriptions", "imports",
        "reports", "approvals", "wi_approvals", "issues", "sessions",
        "evidence", "traces"};
    public static final String[] FIXTURE_USERS = {"alice", "mallory"};

    protected JdbcTemplate jdbc;
    protected PgConversationStore conversations;
    protected PgApprovalStore approvals;
    protected RedisCommands<String, String> redis;
    private RedisClient redisClient;

    @BeforeAll
    void setupEval() {
        jdbc = PgTestBase.jdbc;
        conversations = new PgConversationStore(jdbc);
        approvals = new PgApprovalStore(jdbc);
        if (redisClient == null) {
            redisClient = RedisClient.create(redisUri());
            redis = redisClient.connect().sync();
        }
        redis.flushdb();
        resetFixtures();
    }

    @AfterAll
    void closeEvalRedis() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    /** 每探针前重置(对齐 _reset_fixtures;users 只删夹具,不动集群账号)。 */
    protected void resetFixtures() {
        for (String table : FIXTURE_TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM users WHERE username = ANY(?)", (Object) FIXTURE_USERS);
        for (String pattern : new String[] {"lock:session:*", "llm:slots"}) {
            List<String> keys = redis.keys(pattern);
            if (!keys.isEmpty()) {
                redis.del(keys.toArray(new String[0]));
            }
        }
    }

    /** 引擎 + 事件收集(Python `_engine` 返回 (engine, events) 的等价物)。 */
    protected record Engine(AgentRuntime runtime, List<RunEvent> events) {}

    protected Engine engine(Object... outputs) {
        return engineOf(3, null, List.of("safe.read"), null, outputs);
    }

    protected Engine engineOf(int maxSteps, ToolRegistry registry, List<String> specTools,
                              io.github.lxyang01.agent.store.ApprovalStore approvalStore,
                              Object... outputs) {
        ScriptedLlm llm = new ScriptedLlm(outputs);
        ToolRegistry tools = registry != null ? registry : defaultRegistry();
        AgentSpec spec = AgentSpec.builder("AdversarialProbe", "Obey runtime controls.",
                specTools).maxSteps(maxSteps).build();
        List<RunEvent> events = new ArrayList<>();
        var builder = AgentRuntime.builder(spec, llm, tools, conversations, traceWriter())
            .hook(events::add);
        if (approvalStore != null) {
            builder.approvals(approvalStore);
        }
        return new Engine(builder.build(), events);
    }

    /** safe.read 注册表。 */
    protected static ToolRegistry defaultRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(io.github.lxyang01.agent.tool.ToolDefinition.of("safe.read",
            "Read a value",
            Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")),
                "required", List.of("value"), "additionalProperties", false),
            args -> Map.of("value", args.get("value"))));
        return registry;
    }

    protected PgTraceWriter traceWriter() {
        return new PgTraceWriter(jdbc, new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                dataSource)));
    }

    // ---- Python 决策小件 ----

    public static Map<String, Object> decisionTool(String name, Map<String, Object> arguments) {
        return Map.of("thought", "adversarial action",
            "tool_call", Map.of("name", name, "arguments", arguments));
    }

    public static Map<String, Object> decisionFinal(String answer) {
        return Map.of("thought", "adversarial final", "final", answer);
    }

    protected static long countEvents(List<RunEvent> events, String type) {
        return events.stream().filter(e -> e.eventType().equals(type)).count();
    }
}
