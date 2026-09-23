package io.github.lxyang01.billguard.mcp;

import io.github.lxyang01.billguard.storage.PgWorkItemStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** MCP 侧域 bean 装配(工单存储)。 */
@Configuration
public class DomainConfig {

    @org.springframework.context.annotation.Profile("work-item")
    @Bean
    public PgWorkItemStore pgWorkItemStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new PgWorkItemStore(jdbc, tx);
    }

    @org.springframework.context.annotation.Profile("bill")
    @Bean
    public io.github.lxyang01.billguard.storage.BillRepository billRepository(
        JdbcTemplate jdbc) {
        return new io.github.lxyang01.billguard.storage.BillRepository(jdbc);
    }

    @org.springframework.context.annotation.Profile("bill")
    @Bean
    public io.github.lxyang01.billguard.storage.BillAnomalies billAnomalies(
        JdbcTemplate jdbc,
        io.github.lxyang01.billguard.storage.BillRepository bills) {
        return new io.github.lxyang01.billguard.storage.BillAnomalies(jdbc, bills);
    }

    @Bean
    public TransactionTemplate transactionTemplate(JdbcTemplate jdbc) {
        return new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    }
}
