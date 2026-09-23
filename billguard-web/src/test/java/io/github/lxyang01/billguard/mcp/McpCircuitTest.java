package io.github.lxyang01.billguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 熔断/重连/降级状态机:Mockito 假客户端可控注入,不起真服务。 */
class McpCircuitTest {

    /** 可编程假客户端:按剧本抛异常或返回结果。 */
    static final class FakeClient {
        final McpSyncClient delegate = mock(McpSyncClient.class);
        final Deque<Exception> callFailures = new ArrayDeque<>();
        final Deque<Exception> initializeFailures = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger initializes = new AtomicInteger();

        FakeClient() {
            try {
                when(delegate.initialize()).then(invocation -> {
                    initializes.incrementAndGet();
                    if (!initializeFailures.isEmpty()) {
                        throw initializeFailures.pop();
                    }
                    return new InitializeResult("2025-06-18", null,
                        new Implementation("fake", "1.0"), null);
                });
                when(delegate.listTools(any())).thenReturn(new ListToolsResult(
                    List.of(new Tool("ping", null, "测试",
                        new JsonSchema("object", Map.of(), List.of(), Boolean.TRUE, null, null),
                        null, readOnlyAnnotations(), null)), null));
                when(delegate.listResources(any()))
                    .thenReturn(new ListResourcesResult(List.of(), null));
                when(delegate.listPrompts(any()))
                    .thenReturn(new ListPromptsResult(List.of(), null));
                when(delegate.callTool(any(CallToolRequest.class))).then(invocation -> {
                    calls.incrementAndGet();
                    if (!callFailures.isEmpty()) {
                        throw callFailures.pop();
                    }
                    return new CallToolResult(List.of(new TextContent("{\"ok\":true}")), false);
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        McpClientManager.ConnectionFactory factory() {
            return url -> delegate;
        }
    }

    private static ToolAnnotations readOnlyAnnotations() {
        return new ToolAnnotations(null, true, false, true, false, null);
    }

    @Test
    void degraded_result_on_circuit_open_after_reconnect_exhausted() {
        FakeClient client = new FakeClient();
        McpClientManager manager = new McpClientManager(Duration.ofSeconds(20), null,
            3, 0.01, 60.0, 30.0, client.factory());
        manager.connectStreamableHttp("bill", "http://fake");
        ToolRegistry registry = new ToolRegistry();
        manager.registerTools(registry, "bill");

        // 一次调用失败 + 三次重连失败 → 熔断;调用返回结构化降级而非异常
        client.callFailures.add(new IOException("connection reset"));
        for (int i = 0; i < 3; i++) {
            client.initializeFailures.add(new ConnectException("Connection refused"));
        }
        Object result = registry.execute("bill.ping", Map.of(), null);
        assertThat(result).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("degraded", true);
        assertThat(manager.circuitState("bill")).isEqualTo("open");
        manager.close();
    }

    @Test
    void open_circuit_rejects_further_calls_without_network() {
        FakeClient client = new FakeClient();
        McpClientManager manager = new McpClientManager(Duration.ofSeconds(20), null,
            3, 0.01, 60.0, 30.0, client.factory());
        manager.connectStreamableHttp("bill", "http://fake");
        client.callFailures.add(new IOException("connection reset"));
        for (int i = 0; i < 3; i++) {
            client.initializeFailures.add(new ConnectException("Connection refused"));
        }
        assertThatThrownBy(() -> manager.callTool("bill", "ping", Map.of()))
            .isInstanceOf(McpCircuitOpenException.class)
            .hasMessageContaining("已熔断");
        int callsBefore = client.calls.get();
        // 熔断期内的调用快速失败(不发起网络调用)
        assertThatThrownBy(() -> manager.callTool("bill", "ping", Map.of()))
            .isInstanceOf(McpCircuitOpenException.class)
            .hasMessageContaining("熔断中");
        assertThat(client.calls.get()).isEqualTo(callsBefore);
        manager.close();
    }

    @Test
    void half_open_probe_recovers_after_cooldown() throws Exception {
        FakeClient client = new FakeClient();
        McpClientManager manager = new McpClientManager(Duration.ofSeconds(20), null,
            3, 0.01, 0.15, 30.0, client.factory());
        manager.connectStreamableHttp("bill", "http://fake");
        client.callFailures.add(new IOException("connection reset"));
        for (int i = 0; i < 3; i++) {
            client.initializeFailures.add(new ConnectException("Connection refused"));
        }
        assertThatThrownBy(() -> manager.callTool("bill", "ping", Map.of()))
            .isInstanceOf(McpCircuitOpenException.class);
        assertThat(manager.circuitState("bill")).isEqualTo("open");

        Thread.sleep(200);   // 冷却 0.15s 过 → 半开单探(重连成功)
        Object result = manager.callTool("bill", "ping", Map.of());
        assertThat(result).isNotNull();
        assertThat(manager.circuitState("bill")).isEqualTo("closed");
        manager.close();
    }

    @Test
    void half_open_probe_failure_reopens_circuit() throws Exception {
        FakeClient client = new FakeClient();
        McpClientManager manager = new McpClientManager(Duration.ofSeconds(20), null,
            3, 0.01, 0.15, 30.0, client.factory());
        manager.connectStreamableHttp("bill", "http://fake");
        client.callFailures.add(new IOException("connection reset"));
        for (int i = 0; i < 3; i++) {
            client.initializeFailures.add(new ConnectException("Connection refused"));
        }
        assertThatThrownBy(() -> manager.callTool("bill", "ping", Map.of()))
            .isInstanceOf(McpCircuitOpenException.class);

        Thread.sleep(200);
        // 半开探测:重连仍失败 → 重新 OPEN
        client.initializeFailures.add(new ConnectException("Connection refused"));
        assertThatThrownBy(() -> manager.callTool("bill", "ping", Map.of()))
            .isInstanceOf(McpCircuitOpenException.class)
            .hasMessageContaining("已熔断");
        assertThat(manager.circuitState("bill")).isEqualTo("open");
        manager.close();
    }

    @Test
    void connection_error_classification_by_cause_chain() {
        assertThat(McpClientManager.isConnectionError(new IOException("connection reset")))
            .isTrue();
        // 业务级 JSONRPC error 不是连接类
        assertThat(McpClientManager.isConnectionError(
            new io.modelcontextprotocol.spec.McpError("MCP tool failed: ping"))).isFalse();
        // 关键词:超时消息
        assertThat(McpClientManager.isConnectionError(
            new io.modelcontextprotocol.spec.McpError(
                "Timed out while waiting for response 20s"))).isTrue();
        // 显式因果链参与
        assertThat(McpClientManager.isConnectionError(
            new RuntimeException("call failed", new ConnectException("Connection refused"))))
            .isTrue();
        // suppressed 不参与判定
        RuntimeException withSuppressed = new RuntimeException("business error");
        withSuppressed.addSuppressed(new IOException("connection reset"));
        assertThat(McpClientManager.isConnectionError(withSuppressed)).isFalse();
    }

    @Test
    void tool_policy_derivation_from_meta_and_annotations() {
        var highWrite = new Tool("commit_issue", null, "提交",
            new JsonSchema("object", Map.of(), List.of(), Boolean.TRUE, null, null), null,
            null, Map.of("risk_level", "high_write", "requires_approval", true));
        var info = McpClientManager.toolInfo(highWrite);
        assertThat(info.policy().riskLevel()).isEqualTo(RiskLevel.HIGH_WRITE);
        assertThat(info.policy().requiresApproval()).isTrue();

        var readOnly = new Tool("list", null, "列",
            new JsonSchema("object", Map.of(), List.of(), Boolean.TRUE, null, null), null,
            readOnlyAnnotations(), null);
        assertThat(McpClientManager.toolInfo(readOnly).policy().riskLevel())
            .isEqualTo(RiskLevel.READ);

        // 未知远端写默认高风险 + 需审批
        var unknown = McpClientManager.toolInfo(new Tool("mystery", null, "?",
            new JsonSchema("object", Map.of(), List.of(), Boolean.TRUE, null, null), null,
            null, null));
        assertThat(unknown.policy().riskLevel()).isEqualTo(RiskLevel.HIGH_WRITE);
        assertThat(unknown.policy().requiresApproval()).isTrue();
    }
}
