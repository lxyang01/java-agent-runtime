package io.github.lxyang01.billguard.eval;

import io.github.lxyang01.billguard.BillguardApplication;
import io.github.lxyang01.billguard.auth.UserStore;
import io.github.lxyang01.billguard.coordination.RedisAuthSessions;
import io.github.lxyang01.billguard.coordination.RedisLlmLimiter;
import io.github.lxyang01.billguard.core.BillGuardFacade;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgEvidenceStore;
import io.github.lxyang01.billguard.storage.PgTraceReader;
import io.github.lxyang01.billguard.storage.PgTraceWriter;
import io.github.lxyang01.billguard.storage.PgUserStore;
import io.github.lxyang01.billguard.storage.PgWorkItemStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Web 级探针支撑:独立 Spring 上下文(真实 Security 过滤器链 + 控制器)+
 * 独立 PG/Redis 容器;resetFixtures 与 EvalFixture 同语义。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BillguardApplication.class)
@AutoConfigureMockMvc
public abstract class WebEvalSupport {

    @Container
    protected static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine");

    @Container
    protected static final GenericContainer REDIS =
        new GenericContainer("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void webProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.url",
            () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
    }

    protected static final String[] FIXTURE_TABLES = {
        "tx_audits", "transactions", "categories", "subscriptions", "imports",
        "reports", "approvals", "wi_approvals", "issues", "sessions",
        "evidence", "traces"};
    protected static final String[] FIXTURE_USERS = {"alice", "mallory"};

    protected static JdbcTemplate jdbc;
    protected static RedisCommands<String, String> redis;
    private static RedisClient redisClient;
    protected static BillRepository bills;
    protected static PgWorkItemStore workItems;
    protected static PgApprovalStore approvals;
    protected static UserStore users;
    protected static BillGuardFacade facade;
    protected static MockMvc mockMvc;

    protected void setupWeb(WebApplicationContext context,
                            org.springframework.test.web.servlet.MockMvc autoMockMvc) {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration").load().migrate();
        if (redisClient == null) {
            redisClient = RedisClient.create("redis://" + REDIS.getHost() + ":"
                + REDIS.getMappedPort(6379) + "/0");
            redis = redisClient.connect().sync();
        }
        redis.flushdb();
        resetFixtures();
        bills = new BillRepository(jdbc);
        var tx = traceTx(dataSource);
        workItems = new PgWorkItemStore(jdbc, tx);
        approvals = new PgApprovalStore(jdbc);
        users = new PgUserStore(jdbc, new RedisAuthSessions(redis));
        facade = new BillGuardFacade(bills, new BillAnomalies(jdbc, bills),
            new PgConversationStore(jdbc), new PgApprovalStore(jdbc),
            new PgEvidenceStore(jdbc), new PgTraceReader(jdbc), redis,
            new RedisLlmLimiter(redis, 4),
            (user, sessionId) -> probeAgent(user),
            Duration.ofSeconds(120),
            sessionId -> jdbc.update("DELETE FROM traces WHERE session_id = ?", sessionId));
        facade.setMcpMode(workItems, List::of);
        mockMvc = autoMockMvc;   // @AutoConfigureMockMvc 自动注册全部过滤器链
    }

    /** 探针 Agent:work-items.commit_issue 直连工单存储(_AuthFakeManager 等价)。 */
    protected static io.github.lxyang01.agent.engine.AgentRuntime probeAgent(
            io.github.lxyang01.billguard.auth.User user) {
        var registry = new io.github.lxyang01.agent.tool.ToolRegistry();
        registry.register(new io.github.lxyang01.agent.tool.ToolDefinition(
            "work-items.commit_issue", "Commit approved issue",
            Map.of("type", "object", "properties",
                Map.of("approval_id", Map.of("type", "string")), "required",
                List.of("approval_id"), "additionalProperties", false),
            io.github.lxyang01.agent.policy.ToolPolicy.of(
                io.github.lxyang01.agent.policy.RiskLevel.HIGH_WRITE, true,
                "Creates durable work item"),
            args -> workItems.commitIssue(String.valueOf(args.get("approval_id"))), null));
        // 探针不带技能路由(等价 探针引擎无 skills 装配的路径)
        var spec = io.github.lxyang01.agent.engine.AgentSpec.builder(
            "AdversarialProbe", "Obey runtime controls.", registry.names())
            .maxSteps(3).build();
        return io.github.lxyang01.agent.engine.AgentRuntime.builder(
            spec, new io.github.lxyang01.agent.testing.ScriptedLlm(Map.of("final", "done")),
            registry, new PgConversationStore(jdbc),
            new PgTraceWriter(jdbc, traceTxStatic())).approvals(approvals).build();
    }

    private static org.springframework.transaction.support.TransactionTemplate traceTxStatic() {
        return new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())));
    }

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

    private org.springframework.transaction.support.TransactionTemplate traceTx(
        javax.sql.DataSource dataSource) {
        return new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    protected Map<String, Object> checkpointV2(String sessionId, String traceId,
                                               String userInput) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("schema_version", 2);
        checkpoint.put("full_tool_payloads", List.of());
        checkpoint.put("session_id", sessionId);
        checkpoint.put("trace_id", traceId);
        checkpoint.put("user_input", userInput);
        checkpoint.put("step", 1);
        checkpoint.put("call_id", "c1");
        checkpoint.put("skill_versions", Map.of());
        checkpoint.put("allowed_tools", List.of("work-items.commit_issue"));
        checkpoint.put("execution_summaries", List.of());
        checkpoint.put("artifact_paths", List.of());
        checkpoint.put("completed_tools", List.of());
        return checkpoint;
    }

    protected void saveConversation(String sessionId, String owner) {
        new PgConversationStore(jdbc).save(new io.github.lxyang01.agent.types.Conversation(
            sessionId, "", owner, List.of()));
    }

    protected int loginStatus(String username, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andReturn().getResponse().getStatus();
    }

    protected void cleanupLoginFixture(String throttledUser, String collateralUser) {
        redis.keys("login:fail:*").forEach(key -> redis.del(key));
        redis.keys("auth:token:*").forEach(key -> redis.del(key));
        redis.keys("auth:user:*").forEach(key -> redis.del(key));
    }

    protected void cleanupCsrfFixture(String user) {
        redis.keys("auth:token:*").forEach(key -> redis.del(key));
        redis.keys("auth:user:*").forEach(key -> redis.del(key));
    }
}
