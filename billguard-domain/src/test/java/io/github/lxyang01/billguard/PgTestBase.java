package io.github.lxyang01.billguard;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PG + Redis 测试基座:共享单例容器(整个测试 JVM 一份),Flyway 程序化迁移,
 * 每个测试方法后清空全部业务表 —— 与「独立测试库 + 零残留」语义。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PgTestBase {

    protected static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine")
        .withReuse(true);

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withReuse(true);

    protected static JdbcTemplate jdbc;
    protected static DataSource dataSource;

    @BeforeAll
    void startInfrastructure() {
        if (!PG.isRunning()) {
            PG.start();
        }
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
        if (jdbc == null) {
            dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
            jdbc = new JdbcTemplate(dataSource);
        }
    }

    @AfterEach
    void cleanTables() {
        if (jdbc == null) {
            return;
        }
        for (String table : new String[] {
                "approvals", "wi_approvals", "issues", "traces", "evidence", "sessions",
                "reports", "imports", "tx_audits", "subscriptions", "categories",
                "transactions", "users"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    protected static String redisUri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0";
    }
}
