package io.github.lxyang01.billguard.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.lxyang01.billguard.storage.PgUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 全量业务 API 集成测试(非 LLM 路由;chat 由 Facade 层覆盖)。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final GenericContainer REDIS =
        new GenericContainer("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.url",
            () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
    }

    @Autowired MockMvc mvc;
    @Autowired PgUserStore users;
    @Autowired JdbcTemplate jdbc;

    private static boolean seeded = false;

    @BeforeEach
    void seed() {
        if (!seeded) {
            users.create("alice", "alice-pass-1", "user");
            seeded = true;
        }
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"alice-pass-1\"}"))
            .andExpect(status().isOk()).andReturn();
        return result.getResponse().getHeader("Set-Cookie").split(";")[0];
    }

    @Test
    void bills_import_overview_query_export_roundtrip() throws Exception {
        String cookie = login();
        mvc.perform(post("/api/bills/import").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"filename":"b.csv","csv_text":"交易编号,交易时间,商户,金额\\nA-1,2026-03-01 08:00:00,美团,35.50\\nA-2,2026-03-02 09:00:00,京东,199.00"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.imported_rows").value(2))
            .andExpect(jsonPath("$.result.status").value("completed"));

        mvc.perform(post("/api/bills/overview").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            // 其他测试可能追加数据,不断言绝对总量;只断言默认类别已惰性播种
            .andExpect(jsonPath("$.options.categories[?(@=='餐饮')]").exists());

        mvc.perform(post("/api/bills/query").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"page\":1,\"page_size\":10,\"filters\":{\"merchant\":\"美团\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));

        MvcResult export = mvc.perform(post("/api/bills/export").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn();
        assertThat(export.getResponse().getContentAsString()).contains("A-1").contains("美团");

        mvc.perform(post("/api/bills/anomalies").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":7,\"dimension\":\"duplicate\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dimension").value("duplicate"));
    }

    @Test
    void category_rules_and_workflow_endpoints() throws Exception {
        String cookie = login();
        // 自备数据:本测试独立于其他测试的导入
        String csv = "tx_id,paid_at,merchant,amount\nW-1,2026-03-01 08:00:00,商户W,10.00";
        String importBody = "{\"csv_text\":\""
            + csv.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        mvc.perform(post("/api/bills/import").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(importBody))
            .andExpect(status().isOk());
        mvc.perform(post("/api/category-rules/save").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"打车\",\"keywords\":[\"滴滴\"],\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.name").value("打车"));

        mvc.perform(post("/api/bills/workflow").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tx_ids\":[\"W-1\"],\"updates\":{\"status\":\"待核查\",\"note\":\"核对\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void validation_errors_map_to_400() throws Exception {
        String cookie = login();
        mvc.perform(post("/api/chat").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("message 不能为空"));
        mvc.perform(post("/api/bills/purge").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.purged").value(true));
    }

    @Test
    void snapshot_sessions_approvals_runs_endpoints() throws Exception {
        String cookie = login();
        mvc.perform(post("/api/snapshot").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":\"web-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value("web-1"))
            .andExpect(jsonPath("$.overview").exists())
            .andExpect(jsonPath("$.sessions[?(@.id=='web-1')]").exists());

        mvc.perform(post("/api/approvals/list").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":\"web-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvals").isArray());

        mvc.perform(post("/api/runs/list").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":\"web-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runs").isArray());

        mvc.perform(post("/api/session/delete").header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":\"web-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value("web-1"));   // 只读快照不落会话行
    }

    @Test
    void static_frontend_served_without_auth() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void evaluations_endpoint_returns_list() throws Exception {
        String cookie = login();
        mvc.perform(get("/api/evaluations/list").header("Cookie", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evaluations").isArray());
        // 未登录 401
        mvc.perform(get("/api/evaluations/list")).andExpect(status().isUnauthorized());
    }
}
