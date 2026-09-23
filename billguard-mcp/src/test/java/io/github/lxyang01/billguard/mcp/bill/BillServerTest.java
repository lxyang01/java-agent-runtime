package io.github.lxyang01.billguard.mcp.bill;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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

/** bill server 真实回环:六工具目录/owner 隔离/PII 脱敏出口/meta 策略。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = io.github.lxyang01.billguard.mcp.BillMcpServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("bill")
class BillServerTest {

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
    static boolean seeded = false;

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        if (seeded) {
            return;
        }
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
            + "VALUES ('餐饮', '[\"美团\"]', TRUE, '2026-01-01 00:00:00+00:00', 'alice')");
        Long food = jdbc.queryForObject(
            "SELECT id FROM categories WHERE name='餐饮' AND owner='alice'", Long.class);
        tx("A-1", "2026-03-01 08:00:00", "美团", food, "35.50", "alice", "午餐 SO-1234567");
        tx("A-2", "2026-03-02 09:00:00", "京东", null, "199.00", "alice", "");
        tx("B-1", "2026-03-03 10:00:00", "商户B", null, "50.00", "bob", "");
        seeded = true;
    }

    private void tx(String txId, String paidAt, String merchant, Long categoryId, String amount,
                    String owner, String note) {
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, category_id, "
                + "amount, method, status, created_at, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
            txId, paidAt, merchant, note, categoryId, new java.math.BigDecimal(amount), "支付宝",
            "正常", "2026-03-04 00:00:00+00:00", owner);
    }

    private McpSyncClient connect() {
        McpSyncClient client = McpClient.sync(
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                    .endpoint("/mcp").build())
            .requestTimeout(Duration.ofSeconds(20))
            .capabilities(ClientCapabilities.builder().build())
            .build();
        client.initialize();
        return client;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(McpSyncClient client, String tool,
                                     Map<String, Object> args) {
        CallToolResult result = client.callTool(new CallToolRequest(tool, args));
        assertThat(result.isError()).as("工具 %s 不应出错", tool).isFalse();
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void catalog_policies_and_capabilities() {
        try (McpSyncClient client = connect()) {
            ListToolsResult tools = client.listTools();
            assertThat(tools.tools()).extracting(Tool::name).containsExactlyInAnyOrder(
                "aggregate", "query", "compare_periods", "detect_anomalies", "get_samples",
                "update_status");
            var update = tools.tools().stream()
                .filter(t -> t.name().equals("update_status")).findFirst().orElseThrow();
            assertThat(update.meta())
                .containsEntry("risk_level", "high_write")
                .containsEntry("requires_approval", true)
                .containsEntry("policy_reason", "Updates bill transaction workflow state");
            assertThat(client.listResources().resources()).hasSize(3);
            assertThat(client.listPrompts().prompts()).hasSize(2);
        }
    }

    @Test
    void aggregate_respects_owner_boundary() {
        try (McpSyncClient client = connect()) {
            var alice = call(client, "aggregate", Map.of("owner", "alice"));
            assertThat(((Number) alice.get("count")).longValue()).isEqualTo(2L);
            var bob = call(client, "aggregate", Map.of("owner", "bob"));
            assertThat(((Number) bob.get("count")).longValue()).isEqualTo(1L);
            var legacy = call(client, "aggregate", Map.of());
            assertThat(((Number) legacy.get("count")).longValue()).isZero();   // 空 owner = 仅存量 NULL 行
        }
    }

    @Test
    void query_masks_pii_and_update_status_writes() {
        try (McpSyncClient client = connect()) {
            var result = call(client, "query",
                Map.of("owner", "alice", "merchant", "美团"));
            assertThat(result.get("pii_masked")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            var items = (java.util.List<Map<String, Object>>) result.get("items");
            assertThat(items.get(0).get("note")).isEqualTo("午餐 [订单号]");

            // update_status:operator 必填
            CallToolResult missing = client.callTool(new CallToolRequest("update_status",
                Map.of("tx_ids", java.util.List.of("A-1"), "status", "待核查", "owner", "alice")));
            assertThat(missing.isError()).isTrue();
            // 正常写
            var updated = call(client, "update_status",
                Map.of("tx_ids", java.util.List.of("A-1"), "status", "待核查",
                    "operator", "alice", "owner", "alice"));
            assertThat(updated.get("count")).isEqualTo(1);
        }
    }

    @Test
    void samples_retry_hint_passthrough() {
        try (McpSyncClient client = connect()) {
            var empty = call(client, "get_samples",
                Map.of("owner", "alice", "query", "完全不存在"));
            assertThat(empty.get("matched")).isEqualTo(0);
            assertThat(String.valueOf(empty.get("retry_hint")))
                .startsWith("当前筛选未命中数据");
        }
    }
}
