package io.github.lxyang01.billguard.config;

import io.github.lxyang01.billguard.coordination.RedisLlmLimiter;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgTraceWriter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 运行时装配:PG 仓储 + Redis 协调器(runtime 端口的 billguard-web 实现)。 */
@Configuration
public class RuntimeConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    public PgConversationStore pgConversationStore(JdbcTemplate jdbc) {
        return new PgConversationStore(jdbc);
    }

    @Bean
    public PgApprovalStore pgApprovalStore(JdbcTemplate jdbc) {
        return new PgApprovalStore(jdbc);
    }

    @Bean
    public PgTraceWriter pgTraceWriter(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        return new PgTraceWriter(jdbc, transactionTemplate);
    }

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(
        @Value("${spring.data.redis.url:redis://localhost:6379/0}") String redisUrl) {
        return RedisClient.create(redisUrl);
    }

    @Bean
    public RedisCommands<String, String> redisCommands(RedisClient redisClient) {
        return redisClient.connect().sync();
    }

    @Bean
    public RedisLlmLimiter llmLimiter(RedisCommands<String, String> commands,
                                      @Value("${billguard.llm-slots:4}") int limit) {
        return new RedisLlmLimiter(commands, limit);
    }

    @Bean
    public io.github.lxyang01.billguard.coordination.RedisAuthSessions redisAuthSessions(
        RedisCommands<String, String> commands) {
        return new io.github.lxyang01.billguard.coordination.RedisAuthSessions(commands);
    }

    @Bean
    public io.github.lxyang01.billguard.coordination.RedisLoginThrottle loginThrottle(
        RedisCommands<String, String> commands) {
        return new io.github.lxyang01.billguard.coordination.RedisLoginThrottle(commands);
    }

    @Bean
    public io.github.lxyang01.billguard.storage.PgUserStore pgUserStore(
        JdbcTemplate jdbc,
        io.github.lxyang01.billguard.coordination.RedisAuthSessions sessions) {
        return new io.github.lxyang01.billguard.storage.PgUserStore(jdbc, sessions);
    }

    @Bean
    public io.github.lxyang01.billguard.storage.BillRepository billRepository(JdbcTemplate jdbc) {
        return new io.github.lxyang01.billguard.storage.BillRepository(jdbc);
    }

    @Bean
    public io.github.lxyang01.billguard.storage.BillAnomalies billAnomalies(
        JdbcTemplate jdbc, io.github.lxyang01.billguard.storage.BillRepository bills) {
        return new io.github.lxyang01.billguard.storage.BillAnomalies(jdbc, bills);
    }

    @Bean
    public io.github.lxyang01.billguard.storage.PgEvidenceStore pgEvidenceStore(
        JdbcTemplate jdbc) {
        return new io.github.lxyang01.billguard.storage.PgEvidenceStore(jdbc);
    }

    @Bean
    public io.github.lxyang01.billguard.storage.PgTraceReader pgTraceReader(JdbcTemplate jdbc) {
        return new io.github.lxyang01.billguard.storage.PgTraceReader(jdbc);
    }

    /** MCP 模式:两个 MCP URL 齐备时启用(对齐 Python:缺一启动退出)。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("billguard.mcp.enabled")
    public io.github.lxyang01.billguard.mcp.McpClientManager mcpClientManager(
        @Value("${BILLGUARD_BILL_MCP_URL}") String billUrl,
        @Value("${BILLGUARD_WORK_ITEM_MCP_URL}") String workItemUrl) {
        var manager = new io.github.lxyang01.billguard.mcp.McpClientManager(
            java.time.Duration.ofSeconds(20), null);
        manager.connectStreamableHttp("bill", billUrl);
        manager.connectStreamableHttp("work-items", workItemUrl);
        return manager;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("billguard.mcp.enabled")
    public io.github.lxyang01.billguard.storage.PgWorkItemStore pgWorkItemStore(
        JdbcTemplate jdbc, TransactionTemplate tx) {
        return new io.github.lxyang01.billguard.storage.PgWorkItemStore(jdbc, tx);
    }

    @Bean
    public io.github.lxyang01.billguard.core.BillGuardFacade billGuardFacade(
        JdbcTemplate jdbc,
        io.github.lxyang01.billguard.storage.BillRepository bills,
        io.github.lxyang01.billguard.storage.BillAnomalies anomalies,
        io.github.lxyang01.billguard.storage.PgConversationStore conversations,
        io.github.lxyang01.billguard.storage.PgApprovalStore approvals,
        io.github.lxyang01.billguard.storage.PgEvidenceStore evidence,
        io.github.lxyang01.billguard.storage.PgTraceReader traceReader,
        RedisCommands<String, String> commands,
        RedisLlmLimiter llmSlots,
        org.springframework.ai.chat.model.ChatModel chatModel,
        @Value("${BILLGUARD_LLM_MODEL:openai/gpt-4.1-mini}") String model,
        @Value("${billguard.run-timeout-seconds:120}") long runTimeoutSeconds,
        io.github.lxyang01.billguard.metrics.AppMetrics metrics,
        ObjectProvider<io.github.lxyang01.billguard.mcp.McpClientManager> mcpManagerProvider,
        ObjectProvider<io.github.lxyang01.billguard.storage.PgWorkItemStore> workItemsProvider) {
        var llm = new io.github.lxyang01.billguard.llm.SpringAiLlmClient(chatModel, model, null);
        io.github.lxyang01.billguard.core.BillGuardFacade facade = null;
        facade = new io.github.lxyang01.billguard.core.BillGuardFacade(bills, anomalies,
            conversations, approvals, evidence, traceReader, commands, llmSlots,
            (user, sessionId) -> mcpManagerProvider.getIfAvailable() != null
                ? mcpAgent(mcpManagerProvider.getObject(), llm,
                    java.time.Duration.ofSeconds(runTimeoutSeconds), conversations,
                    traceRef(jdbc), approvals, user.username())
                : io.github.lxyang01.billguard.bills.BillAgentFactory
                    .createLocalAgent(llm, java.time.Duration.ofSeconds(runTimeoutSeconds),
                        conversations, traceRef(jdbc), bills, anomalies, user.username()),
            java.time.Duration.ofSeconds(runTimeoutSeconds),
            sessionId -> jdbc.update("DELETE FROM traces WHERE session_id = ?", sessionId));
        facade.setMetrics(metrics);
        var mcpManager = mcpManagerProvider.getIfAvailable();
        if (mcpManager != null) {
            var workItems = workItemsProvider.getIfAvailable();
            // MCP 模式:远端工具目录 + owner 身份注入(模型不可见、不可伪造)
            facade.setMcpMode(workItems,
                () -> mcpManager.snapshots().stream()
                    .map(io.github.lxyang01.billguard.mcp.McpServerSnapshot::view).toList());
        }
        return facade;
    }

    /** MCP 模式 Agent:远端工具目录 + owner 注入(对齐 create_mcp_bill_agent)。 */
    private static io.github.lxyang01.agent.engine.AgentRuntime mcpAgent(
        io.github.lxyang01.billguard.mcp.McpClientManager manager,
        io.github.lxyang01.agent.llm.LlmClient llm, java.time.Duration runTimeout,
        io.github.lxyang01.billguard.storage.PgConversationStore conversations,
        io.github.lxyang01.agent.store.TraceWriter traceWriter,
        io.github.lxyang01.agent.store.ApprovalStore approvals, String username) {
        io.github.lxyang01.agent.tool.ToolRegistry raw = new io.github.lxyang01.agent.tool.ToolRegistry();
        for (var snapshot : manager.snapshots()) {
            manager.registerTools(raw, snapshot.name());
        }
        var injected = io.github.lxyang01.billguard.mcp.OwnerIdentity
            .injectOwnerIdentity(raw, username);
        return io.github.lxyang01.billguard.bills.BillAgentFactory.createMcpAgent(
            llm, runTimeout, conversations, traceWriter, approvals, injected);
    }

    private static io.github.lxyang01.agent.store.TraceWriter traceRef(JdbcTemplate jdbc) {
        return new io.github.lxyang01.billguard.storage.PgTraceWriter(jdbc,
            new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    jdbc.getDataSource())));
    }

    @Bean
    public io.github.lxyang01.billguard.auth.Authenticator authenticator(
        io.github.lxyang01.billguard.storage.PgUserStore users,
        io.github.lxyang01.billguard.coordination.RedisAuthSessions sessions) {
        return new io.github.lxyang01.billguard.auth.Authenticator(users, sessions);
    }
}
