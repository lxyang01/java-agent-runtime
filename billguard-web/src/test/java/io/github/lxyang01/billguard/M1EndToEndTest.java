package io.github.lxyang01.billguard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.agent.engine.AgentRuntime;
import io.github.lxyang01.agent.engine.AgentSpec;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.billguard.coordination.RedisSessionLock;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgTraceWriter;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M1 端到端:真实 Spring 装配 + 真实 PG(Flyway 迁移)+ 真实 Redis,
 * 完整走通「高写工具 → 审批暂停 → 人工批准 → 恢复执行 → 完成」。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class M1EndToEndTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final GenericContainer REDIS =
        new GenericContainer("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.url",
            () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
    }

    @Autowired PgConversationStore conversations;
    @Autowired PgApprovalStore approvals;
    @Autowired PgTraceWriter traces;
    @Autowired JdbcTemplate jdbc;
    @Autowired RedisCommands<String, String> redisCommands;

    @BeforeEach
    void cleanBusinessTables() {
        redisCommands.flushdb();
        for (String table : new String[] {"approvals", "traces", "sessions"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private AgentRuntime demoAgent(io.github.lxyang01.agent.llm.LlmClient llm) {
        ToolRegistry tools = new ToolRegistry();
        tools.register(ToolDefinition.of("demo.read", "读取", Map.of(
            "type", "object", "properties", Map.of(), "required", List.of()),
            args -> Map.of("count", 3)));
        tools.register(new ToolDefinition("demo.write", "高风险写", Map.of(
            "type", "object",
            "properties", Map.of("value", Map.of("type", "string")),
            "required", List.of("value"), "additionalProperties", false),
            ToolPolicy.of(RiskLevel.HIGH_WRITE, false, "高风险写操作"),
            args -> Map.of("written", args.get("value"), "storage_path", "demo://out"),
            result -> "已写入 " + ((Map<?, ?>) result).get("written")));
        AgentSpec spec = AgentSpec.builder("e2e-agent", "端到端测试",
            List.of("demo.read", "demo.write")).build();
        return AgentRuntime.builder(spec, llm, tools, conversations, traces)
            .approvals(approvals)
            .build();
    }

    @Test
    void approval_pause_resume_full_path_on_real_pg_and_redis() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.write",
                "arguments", Map.of("value", "重要数据"))),
            Map.of("final", "已完成写入"));
        AgentRuntime agent = demoAgent(llm);

        var first = agent.run("e2e-1", "执行写入");
        assertThat(first.status()).isEqualTo("approval_pending");
        String approvalId = (String) first.approval().get("id");

        approvals.decide(approvalId, true, "admin", "同意");
        var done = agent.resume(approvalId);
        assertThat(done.status()).isEqualTo("completed");

        // 审批卡:executed
        assertThat(approvals.get(approvalId).status()).isEqualTo("executed");
        // 会话持久化:末条 assistant 答案含写入结论
        var messages = conversations.load("e2e-1").messages();
        assertThat(messages.get(messages.size() - 1).content()).contains("已完成写入");
        // 追踪持久化:该 trace 事件数 ≥ 8(run_start/model/tool/approval/resume/run_end)
        Integer events = jdbc.queryForObject(
            "SELECT jsonb_array_length(events) FROM traces WHERE session_id = ? AND trace_id = ?",
            Integer.class, "e2e-1", first.traceId());
        assertThat(events).isGreaterThanOrEqualTo(8);
    }

    @Test
    void session_lock_semantics_via_real_redis() {
        var held = RedisSessionLock.acquire(redisCommands, "e2e-lock",
            Duration.ofSeconds(30));
        assertThat(held).isPresent();
        assertThat(RedisSessionLock.acquire(redisCommands, "e2e-lock", Duration.ofSeconds(30)))
            .isEmpty();
        held.get().release();
        assertThat(RedisSessionLock.acquire(redisCommands, "e2e-lock", Duration.ofSeconds(30)))
            .isPresent();
    }
}
