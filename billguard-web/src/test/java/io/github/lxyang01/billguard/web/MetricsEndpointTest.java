package io.github.lxyang01.billguard.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.lxyang01.billguard.storage.PgUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** /api/metrics:登录门槛、形状、计数器随请求增长。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class MetricsEndpointTest {

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

    private static boolean seeded = false;

    @org.junit.jupiter.api.BeforeEach
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
    void metrics_requires_login() throws Exception {
        mvc.perform(get("/api/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void metrics_shape_and_counters_grow() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());   // 制造一些计数
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"wrong-pass\"}"))
            .andExpect(status().isUnauthorized());

        String cookie = login();
        mvc.perform(get("/api/metrics").header("Cookie", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.instance").isNotEmpty())
            .andExpect(jsonPath("$.uptime_seconds").isNumber())
            .andExpect(jsonPath("$.counters.http_requests_total").isNumber())
            .andExpect(jsonPath("$.counters.http_status_200").isNumber())
            .andExpect(jsonPath("$.counters.http_status_401").isNumber())
            .andExpect(jsonPath("$.counters.login_failures_total").value(1))
            .andExpect(jsonPath("$.gauges.llm_slots_in_use").isNumber())
            .andExpect(jsonPath("$.timings").isMap())
            .andExpect(jsonPath("$.note").isNotEmpty());
    }
}
