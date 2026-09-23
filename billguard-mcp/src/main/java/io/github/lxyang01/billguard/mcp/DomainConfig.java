package io.github.lxyang01.billguard.mcp;

import io.github.lxyang01.billguard.storage.PgWorkItemStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** MCP 侧域 bean 装配(工单存储)。 */
@Configuration
public class DomainConfig {

    @Bean
    public PgWorkItemStore pgWorkItemStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new PgWorkItemStore(jdbc, tx);
    }

    @Bean
    public TransactionTemplate transactionTemplate(JdbcTemplate jdbc) {
        return new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    }
}
