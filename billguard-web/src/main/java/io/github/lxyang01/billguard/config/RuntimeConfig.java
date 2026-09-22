package io.github.lxyang01.billguard.config;

import io.github.lxyang01.billguard.coordination.RedisLlmLimiter;
import io.github.lxyang01.billguard.storage.PgApprovalStore;
import io.github.lxyang01.billguard.storage.PgConversationStore;
import io.github.lxyang01.billguard.storage.PgTraceWriter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import javax.sql.DataSource;
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
        io.github.lxyang01.billguard.metrics.AppMetrics metrics) {
        var llm = new io.github.lxyang01.billguard.llm.SpringAiLlmClient(chatModel, model, null);
        io.github.lxyang01.billguard.core.BillGuardFacade facade = null;
        facade = new io.github.lxyang01.billguard.core.BillGuardFacade(bills, anomalies,
            conversations, approvals, evidence, traceReader, commands, llmSlots,
            (user, sessionId) -> io.github.lxyang01.billguard.bills.BillAgentFactory
                .createLocalAgent(llm, java.time.Duration.ofSeconds(runTimeoutSeconds),
                    conversations, traceRef(jdbc), bills, anomalies, user.username()),
            java.time.Duration.ofSeconds(runTimeoutSeconds),
            sessionId -> jdbc.update("DELETE FROM traces WHERE session_id = ?", sessionId));
        facade.setMetrics(metrics);
        return facade;
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
