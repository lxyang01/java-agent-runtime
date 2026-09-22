package io.github.lxyang01.billguard.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.lxyang01.billguard.storage.PgUserStore;
import io.lettuce.core.api.sync.RedisCommands;
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

/** 安全三件套 + 登录流端到端(真实 PG/Redis + 完整过滤器链)。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAndAuthFlowTest {

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
    @Autowired RedisCommands<String, String> redisCommands;

    private static boolean seeded = false;

    @BeforeEach
    void seedUsersAndCleanThrottle() {
        if (!seeded) {
            users.create("admin", "admin-pass-1", "admin");
            users.create("alice", "alice-pass-1", "user");
            seeded = true;
        }
        redisCommands.keys("login:fail:*").forEach(key -> redisCommands.del(key));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).startsWith("session=").contains("HttpOnly").contains("SameSite=Strict");
        return cookie.split(";")[0];
    }

    @Test
    void health_open_without_auth() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void protected_api_without_cookie_401() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("未登录或会话已失效"));
    }

    @Test
    void invalid_token_401_expired_message() throws Exception {
        mvc.perform(get("/api/auth/me").header("Cookie", "session=not-a-real-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("登录已过期,请重新登录"));
    }

    @Test
    void login_validation_before_csrf_check() throws Exception {
        // 空凭据 + 恶意 Origin:优先报参数错误(不给攻击者探测差异的信息)
        mvc.perform(post("/api/auth/login")
                .header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("用户名和密码不能为空"));
    }

    @Test
    void login_cross_origin_rejected() throws Exception {
        mvc.perform(post("/api/auth/login")
                .header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-pass-1\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("跨站请求被拒绝"));
    }

    @Test
    void login_success_sets_cookie_and_me_works() throws Exception {
        String cookie = login("admin", "admin-pass-1");
        mvc.perform(get("/api/auth/me").header("Cookie", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void wrong_password_counts_and_locks_after_five() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"alice\",\"password\":\"bad-pass-x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("用户名或密码错误"));
        }
        // 第 6 次即使密码正确也被锁(429)
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"alice-pass-1\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("登录失败次数过多,请稍后再试"));
    }

    @Test
    void csrf_blocks_authenticated_cross_origin_post() throws Exception {
        String cookie = login("admin", "admin-pass-1");
        mvc.perform(post("/api/auth/logout")
                .header("Cookie", cookie)
                .header("Origin", "https://evil.example"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("跨站请求被拒绝"));
        // 无 Origin/Referer 的非浏览器客户端放行
        mvc.perform(post("/api/auth/logout").header("Cookie", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void logout_invalidates_token() throws Exception {
        String cookie = login("admin", "admin-pass-1");
        mvc.perform(post("/api/auth/logout").header("Cookie", cookie))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Cookie", cookie))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void capability_gate_blocks_user_from_admin_post() throws Exception {
        String cookie = login("alice", "alice-pass-1");
        mvc.perform(post("/api/admin/users")
                .header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"pass-1234\",\"role\":\"user\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("当前角色无权执行此操作"));
        // GET 侧由控制器以专属文案拒绝
        mvc.perform(get("/api/admin/users").header("Cookie", cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("仅管理员可管理用户"));
    }

    @Test
    void admin_can_list_and_create_users() throws Exception {
        String cookie = login("admin", "admin-pass-1");
        mvc.perform(get("/api/admin/users").header("Cookie", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.users[?(@.username=='admin')]").exists());
        mvc.perform(post("/api/admin/users")
                .header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"pass-1234\",\"role\":\"user\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.username").value("newuser"));
    }
}
