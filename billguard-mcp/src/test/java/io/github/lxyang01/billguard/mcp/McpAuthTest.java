package io.github.lxyang01.billguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** MCP 服务端认证(fail-closed 密钥 + 服务端 owner 终裁)。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.github.lxyang01.billguard.mcp.BillMcpServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "BILLGUARD_MCP_API_KEY=test-service-key")
@ActiveProfiles("bill")
class McpAuthTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private McpSyncClient connect(String apiKey, String owner) {
        var builder = HttpClientStreamableHttpTransport
            .builder("http://localhost:" + port).endpoint("/mcp");
        if (apiKey != null || owner != null) {
            builder.customizeRequest(request -> {
                if (apiKey != null) {
                    request.header("X-BillGuard-Api-Key", apiKey);
                }
                if (owner != null) {
                    request.header("X-BillGuard-Owner", owner);
                }
            });
        }
        var client = McpClient.sync(builder.build())
            .requestTimeout(Duration.ofSeconds(10))
            .capabilities(ClientCapabilities.builder().build())
            .build();
        client.initialize();
        return client;
    }

    @Test
    void missing_key_rejected_before_protocol() {
        // 无密钥:HTTP 401,MCP 初始化不可达
        boolean failed = false;
        try (var client = connect(null, null)) {
            client.listTools();
        } catch (Exception e) {
            failed = true;
        }
        assertThat(failed).as("无密钥调用必须失败").isTrue();
    }

    @Test
    void wrong_key_rejected() {
        boolean failed = false;
        try (var client = connect("wrong-key", null)) {
            client.listTools();
        } catch (Exception e) {
            failed = true;
        }
        assertThat(failed).as("错密钥调用必须失败").isTrue();
    }

    @Test
    void server_overrides_client_owner_with_authenticated_identity() {
        // alice 名下一笔;mallory 认证身份但参数伪造 owner=alice → 只能看到 mallory(空)
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
            + "VALUES ('餐饮', '[]', TRUE, '2026-01-01 00:00:00+00:00', 'alice')");
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, category_id, "
                + "amount, method, status, created_at, owner) "
            + "VALUES ('M-1','2026-03-01 08:00:00','美团','',"
            + "(SELECT id FROM categories WHERE owner='alice'),25.00,'支付宝','正常',"
            + "'2026-03-02 00:00:00+00:00','alice')");
        try (var client = connect("test-service-key", "mallory")) {
            // 参数伪造 owner=alice
            var result = client.callTool(new CallToolRequest("aggregate",
                Map.of("owner", "alice")));
            assertThat(result.isError()).isFalse();
            @SuppressWarnings("unchecked")
            var payload = (Map<String, Object>) result.structuredContent();
            assertThat(((Number) payload.get("count")).longValue())
                .as("服务端以认证身份(mallory)裁定租户,伪造 owner 无效")
                .isZero();
        }
        try (var client = connect("test-service-key", "alice")) {
            var result = client.callTool(new CallToolRequest("aggregate", Map.of()));
            @SuppressWarnings("unchecked")
            var payload = (Map<String, Object>) result.structuredContent();
            assertThat(((Number) payload.get("count")).longValue())
                .as("认证身份 alice 能看到自己数据")
                .isEqualTo(1);
        }
    }
}
