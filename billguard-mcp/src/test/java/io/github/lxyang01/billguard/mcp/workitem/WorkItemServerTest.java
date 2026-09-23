package io.github.lxyang01.billguard.mcp.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.billguard.storage.PgWorkItemStore;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** work-item server 真实回环:HTTP 起服 → 真 MCP 客户端 initialize/listTools/callTool。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = io.github.lxyang01.billguard.mcp.WorkItemMcpServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("work-item")
class WorkItemServerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("BILLGUARD_MCP_API_KEY", () -> "test-key");
    }

    @Autowired PgWorkItemStore store;
    @LocalServerPort int port;

    private McpSyncClient connect() {
        var transport = HttpClientStreamableHttpTransport.builder(
                "http://localhost:" + port).endpoint("/mcp");
        transport.customizeRequest(request ->
            request.header("X-BillGuard-Api-Key", "test-key"));
        McpSyncClient client = McpClient.sync(transport.build())
            .requestTimeout(Duration.ofSeconds(20))
            .capabilities(ClientCapabilities.builder().build())
            .build();
        client.initialize();
        return client;
    }

    @Test
    void tools_catalog_and_policy_metadata() {
        try (McpSyncClient client = connect()) {
            ListToolsResult tools = client.listTools();
            assertThat(tools.tools()).extracting(Tool::name)
                .containsExactlyInAnyOrder("list_issues", "get_issue", "prepare_issue",
                    "commit_issue");
            // approve 刻意不暴露
            assertThat(tools.tools()).noneMatch(t -> t.name().contains("approve"));
            var commit = tools.tools().stream()
                .filter(t -> t.name().equals("commit_issue")).findFirst().orElseThrow();
            assertThat(commit.meta())
                .containsEntry("risk_level", "high_write")
                .containsEntry("requires_approval", true);
            var prepare = tools.tools().stream()
                .filter(t -> t.name().equals("prepare_issue")).findFirst().orElseThrow();
            assertThat(prepare.meta()).containsEntry("risk_level", "low_write");
            // 资源与 prompt 目录
            assertThat(client.listResources().resources())
                .anyMatch(r -> "work-items://schema".equals(r.uri()));
            assertThat(client.listPrompts().prompts())
                .anyMatch(p -> "create-bill-action-item".equals(p.name()));
        }
    }

    @Test
    void prepare_commit_three_phase_over_mcp() {
        try (McpSyncClient client = connect()) {
            CallToolResult prepared = client.callTool(new CallToolRequest("prepare_issue",
                Map.of("title", "取消音乐订阅", "description", "用户要求退款",
                    "priority", "high")));
            assertThat(prepared.isError()).isFalse();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) prepared.structuredContent();
            String approvalId = String.valueOf(payload.get("approval_id"));
            assertThat(approvalId).startsWith("APR-");
            assertThat(String.valueOf(payload.get("next_step")))
                .contains("必须紧接着调用 commit_issue");

            // 未批准 commit 必定失败(第二道闸)
            CallToolResult early = client.callTool(
                new CallToolRequest("commit_issue", Map.of("approval_id", approvalId)));
            assertThat(early.isError()).isTrue();

            // 通道外人工批准(直连存储,不经 MCP)
            store.decide(approvalId, true, "admin");
            CallToolResult committed = client.callTool(
                new CallToolRequest("commit_issue", Map.of("approval_id", approvalId)));
            assertThat(committed.isError()).isFalse();
            @SuppressWarnings("unchecked")
            Map<String, Object> created = (Map<String, Object>) committed.structuredContent();
            @SuppressWarnings("unchecked")
            Map<String, Object> issue = (Map<String, Object>) created.get("created");
            assertThat(String.valueOf(issue.get("id"))).startsWith("ISS-");
            assertThat(issue.get("title")).isEqualTo("取消音乐订阅");
        }
    }
}
