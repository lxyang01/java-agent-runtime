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
}
