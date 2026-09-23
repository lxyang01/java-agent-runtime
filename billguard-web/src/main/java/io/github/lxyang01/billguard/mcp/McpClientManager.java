package io.github.lxyang01.billguard.mcp;

import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.agent.util.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * MCP 客户端管理器:持久连接 + 同步超时 facade + 有界重连(1s/2s/4s×3)
 * + 熔断(CLOSED/OPEN 60s/HALF_OPEN 单探)+ 结构化降级。逐语义对齐 mcp_runtime.py。
 */
public final class McpClientManager implements AutoCloseable {

    private static final Pattern SERVER_NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /** 连接类异常白名单(对齐 _CONNECTION_ERROR_TYPES)。 */
    private static final List<Class<? extends Throwable>> CONNECTION_TYPES = List.of(
        java.io.IOException.class,
        java.util.concurrent.TimeoutException.class,
        io.modelcontextprotocol.spec.McpError.class);

    /** 报文关键词(逐字)。 */
    private static final List<String> CONNECTION_HINTS = List.of(
        "connection closed", "server disconnected", "peer closed",
        "connection refused", "connection reset", "connection aborted",
        "connect call failed", "closedresource", "brokenresource",
        "timed out while waiting for response");

    /** 连接工厂(测试注入假实现;生产 = 真 HTTP 连接)。 */
    public interface ConnectionFactory {
        McpSyncClient connect(String url);

        default void close(McpSyncClient client) {
            client.close();
        }
    }

    private record Connection(McpSyncClient client, McpServerSnapshot snapshot) {}

    private record ServerSpec(String url) {}

    private final Duration requestTimeout;
    private final ConnectionFactory connectionFactory;
    private final Consumer<Map<String, Object>> auditHook;
    private final int reconnectAttempts;
    private final double reconnectBackoffBaseSeconds;
    private final double circuitCooldownSeconds;
    private final double halfOpenExpirySeconds;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, McpServerSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, ServerSpec> specs = new LinkedHashMap<>();
    private final Map<String, CircuitState> circuits = new LinkedHashMap<>();
    private boolean closed = false;

    public McpClientManager(Duration requestTimeout, Consumer<Map<String, Object>> auditHook) {
        this(requestTimeout, auditHook, 3, 1.0, 60.0, 30.0);
    }

    public McpClientManager(Duration requestTimeout, Consumer<Map<String, Object>> auditHook,
                            ConnectionFactory connectionFactory) {
        this(requestTimeout, auditHook, 3, 1.0, 60.0, 30.0, connectionFactory);
    }

    public McpClientManager(Duration requestTimeout, Consumer<Map<String, Object>> auditHook,
                            int reconnectAttempts, double reconnectBackoffBaseSeconds,
                            double circuitCooldownSeconds, double halfOpenExpirySeconds,
                            ConnectionFactory connectionFactory) {
        this.requestTimeout = requestTimeout;
        this.auditHook = auditHook;
        this.reconnectAttempts = reconnectAttempts;
        this.reconnectBackoffBaseSeconds = reconnectBackoffBaseSeconds;
        this.circuitCooldownSeconds = circuitCooldownSeconds;
        this.halfOpenExpirySeconds = halfOpenExpirySeconds;
        this.connectionFactory = connectionFactory != null ? connectionFactory
            : defaultFactory(requestTimeout);
    }

    public McpClientManager(Duration requestTimeout, Consumer<Map<String, Object>> auditHook,
                            int reconnectAttempts, double reconnectBackoffBaseSeconds,
                            double circuitCooldownSeconds, double halfOpenExpirySeconds) {
        this(requestTimeout, auditHook, reconnectAttempts, reconnectBackoffBaseSeconds,
            circuitCooldownSeconds, halfOpenExpirySeconds, null);
    }

    private static ConnectionFactory defaultFactory(Duration requestTimeout) {
        return url -> McpClient.sync(
                HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp").build())
            .requestTimeout(requestTimeout)
            .capabilities(ClientCapabilities.builder().build())
            .build();
    }

    private ConnectionFactory factory() {
        return connectionFactory;
    }

    // ---- 连接与目录 ----

    public McpServerSnapshot connectStreamableHttp(String name, String url) {
        validateNewName(name);
        stateLock.lock();
        try {
            specs.put(name, new ServerSpec(url));
            circuits.put(name, new CircuitState());
        } finally {
            stateLock.unlock();
        }
        McpSyncClient client = factory().connect(url);
        McpServerSnapshot snapshot = discover(name, "streamable-http", client);
        stateLock.lock();
        try {
            connections.put(name, new Connection(client, snapshot));
            snapshots.put(name, snapshot);
        } finally {
            stateLock.unlock();
        }
        audit("mcp_connected", Map.of("server", name, "transport", "streamable-http",
            "protocol_version", snapshot.protocolVersion()));
        return snapshot;
    }

    private McpServerSnapshot discover(String name, String transport, McpSyncClient client) {
        var initialize = client.initialize();
        List<McpToolInfo> tools = new ArrayList<>();
        String cursor = null;
        do {
            ListToolsResult page = client.listTools(cursor);
            page.tools().forEach(tool -> tools.add(toolInfo(tool)));
            cursor = page.nextCursor();
        } while (cursor != null);
        List<String> resources = new ArrayList<>();
        String rc = null;
        do {
            ListResourcesResult page = client.listResources(rc);
            page.resources().forEach(r -> resources.add(String.valueOf(r.uri())));
            rc = page.nextCursor();
        } while (rc != null);
        List<String> prompts = new ArrayList<>();
        String pc = null;
        do {
            ListPromptsResult page = client.listPrompts(pc);
            page.prompts().forEach(p -> prompts.add(String.valueOf(p.name())));
            pc = page.nextCursor();
        } while (pc != null);
        return new McpServerSnapshot(name, transport,
            initialize.serverInfo().name(), initialize.serverInfo().version(),
            initialize.protocolVersion(), List.copyOf(tools), List.copyOf(resources),
            List.copyOf(prompts));
    }

    /** 远端策略推导:meta.risk_level 优先;annotations 兜底;未知写默认 high_write。 */
    static McpToolInfo toolInfo(io.modelcontextprotocol.spec.McpSchema.Tool tool) {
        Map<String, Object> meta = tool.meta() == null ? Map.of() : tool.meta();
        String risk = String.valueOf(meta.getOrDefault("risk_level", ""));
        if (!List.of("read", "low_write", "high_write", "forbidden").contains(risk)) {
            ToolAnnotations annotations = tool.annotations();
            if (annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint())) {
                risk = "read";
            } else if (annotations != null && Boolean.TRUE.equals(annotations.destructiveHint())) {
                risk = "high_write";
            } else {
                risk = "high_write";   // 未知远端写默认高风险
            }
        }
        boolean requiresApproval = meta.containsKey("requires_approval")
            ? Boolean.TRUE.equals(meta.get("requires_approval"))
            : "high_write".equals(risk);
        String reason = String.valueOf(meta.getOrDefault("policy_reason",
            "read".equals(risk) ? "Remote tool is read-only"
                : "Remote tool changes business state"));
        ToolPolicy policy = new ToolPolicy(RiskLevel.from(risk), requiresApproval, reason);
        Map<String, Object> schema = new LinkedHashMap<>();
        if (tool.inputSchema() != null) {
            if (tool.inputSchema().properties() != null) {
                schema.put("properties", tool.inputSchema().properties());
            }
            schema.put("required", tool.inputSchema().required() == null
                ? List.of() : tool.inputSchema().required());
            schema.put("type", "object");
            schema.put("additionalProperties",
                tool.inputSchema().additionalProperties() == null
                    ? Boolean.TRUE : tool.inputSchema().additionalProperties());
        }
        return new McpToolInfo(tool.name(),
            tool.description() == null ? "" : tool.description(), schema, policy);
    }

    public List<McpServerSnapshot> snapshots() {
        stateLock.lock();
        try {
            return snapshots.keySet().stream().sorted()
                .map(snapshots::get).toList();
        } finally {
            stateLock.unlock();
        }
    }

    public String circuitState(String server) {
        stateLock.lock();
        try {
            CircuitState circuit = circuits.get(server);
            return circuit == null ? "closed"
                : circuit.phase.name().toLowerCase(java.util.Locale.ROOT);
        } finally {
            stateLock.unlock();
        }
    }

    /** 注册远端工具为 `{server}.{tool}`,handler 捕获熔断异常转结构化降级。 */
    public List<String> registerTools(ToolRegistry registry, String serverName) {
        McpServerSnapshot snapshot = snapshot(serverName);
        List<String> registered = new ArrayList<>();
        for (McpToolInfo remote : snapshot.tools()) {
            String localName = serverName + "." + remote.name();
            registry.register(new ToolDefinition(localName,
                ("[MCP:" + serverName + "] " + remote.description()).strip(),
                remote.inputSchema(), remote.policy(),
                arguments -> {
                    try {
                        return callTool(serverName, remote.name(), arguments);
                    } catch (McpCircuitOpenException e) {
                        // Agent 循环存活;高写降级由引擎按风险记未执行
                        Map<String, Object> degraded = new LinkedHashMap<>();
                        degraded.put("error", e.getMessage());
                        degraded.put("degraded", true);
                        return degraded;
                    }
                }, null));
            registered.add(localName);
        }
        return List.copyOf(registered);
    }

    // ---- 调用与韧性 ----

    public Object callTool(String server, String tool, Map<String, Object> arguments) {
        audit("mcp_tool_start", Map.of("server", server, "tool", tool,
            "arguments", arguments == null ? Map.of() : arguments));
        try {
            Object result = guardedCall(server, tool, arguments == null ? Map.of() : arguments);
            audit("mcp_tool_end", Map.of("server", server, "tool", tool, "result", result));
            return result;
        } catch (RuntimeException e) {
            audit("mcp_tool_error", Map.of("server", server, "tool", tool,
                "error", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    private Object guardedCall(String server, String tool, Map<String, Object> arguments) {
        String mode = admitCall(server);   // closed | half_open;拒绝时抛 CircuitOpen
        boolean halfOpenDropped = false;
        stateLock.lock();
        try {
            halfOpenDropped = "half_open".equals(mode) && !connections.containsKey(server);
        } finally {
            stateLock.unlock();
        }
        if (halfOpenDropped) {
            // 重连耗尽时连接已被拆除:半开探测 = 一次"重连 + 调用"
            return recoverAndRetry(server, tool, arguments, mode,
                new McpException("MCP connection to " + server + " was dropped"));
        }
        Object result;
        try {
            result = invoke(server, tool, arguments);
        } catch (McpException e) {
            if (!isConnectionError(e)) {
                if ("half_open".equals(mode)) {
                    // 半开探测中服务可达但本次调用失败(如参数错误):连接已恢复
                    markRecovered(server);
                }
                throw e;
            }
            return recoverAndRetry(server, tool, arguments, mode, e);
        }
        if ("half_open".equals(mode)) {
            markRecovered(server);   // 探测成功(无需重连):熔断关闭
        }
        return result;
    }

    /** 熔断闸门:返回 closed/half_open,拒绝时抛 CircuitOpen。 */
    private String admitCall(String server) {
        stateLock.lock();
        try {
            CircuitState circuit = circuits.get(server);
            if (circuit == null) {
                return "closed";
            }
            if (circuit.recovering) {
                throw new McpCircuitOpenException(
                    "MCP 服务 " + server + " 正在重连,请稍后重试");
            }
            switch (circuit.phase) {
                case OPEN -> {
                    double remaining = circuit.openedAt + circuitCooldownSeconds - mono();
                    if (remaining > 0) {
                        throw new McpCircuitOpenException("MCP 服务 " + server
                            + " 暂时不可用(熔断中,剩余 "
                            + (int) Math.ceil(remaining) + " 秒),请稍后重试");
                    }
                    circuit.phase = CircuitState.Phase.HALF_OPEN;
                    circuit.halfOpenAt = mono();
                    circuit.recovering = true;   // 半开只放一次探测
                    audit("mcp_circuit_half_open", Map.of("server", server));
                    return "half_open";
                }
                case HALF_OPEN -> {
                    if (!circuit.recovering
                        && mono() - circuit.halfOpenAt > halfOpenExpirySeconds) {
                        // 探测线程异常中断留下的过期半开:重新放行一次,避免永久快速失败
                        circuit.halfOpenAt = mono();
                        circuit.recovering = true;
                        audit("mcp_circuit_half_open",
                            Map.of("server", server, "expired", true));
                        return "half_open";
                    }
                    throw new McpCircuitOpenException(
                        "MCP 服务 " + server + " 正在探测恢复,请稍后重试");
                }
                default -> {
                    return "closed";
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 连接类失败后的有界恢复。closed:退避 base*2^n 逐次"重连+重试",
     * 全部失败 → OPEN;half_open:立即单次探测,失败 → 重新 OPEN。
     */
    private Object recoverAndRetry(String server, String tool, Map<String, Object> arguments,
                                   String mode, McpException failure) {
        int attempts = "closed".equals(mode) ? reconnectAttempts : 1;
        stateLock.lock();
        try {
            CircuitState circuit = circuits.computeIfAbsent(server, k -> new CircuitState());
            if (circuit.recovering && !"half_open".equals(mode)) {
                throw new McpCircuitOpenException(
                    "MCP 服务 " + server + " 正在重连,请稍后重试");
            }
            circuit.recovering = true;
        } finally {
            stateLock.unlock();
        }
        McpException lastError = failure;
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                double delay = "closed".equals(mode)
                    ? reconnectBackoffBaseSeconds * (1 << (attempt - 1)) : 0.0;
                if (delay > 0) {
                    sleep(delay);
                }
                audit("mcp_retry", Map.of("server", server, "attempt", attempt,
                    "delay", delay, "error", String.valueOf(lastError.getMessage())));
                try {
                    reconnect(server);
                } catch (Exception reconnectExc) {
                    // 重连失败(任何类别)= 会话未恢复,只算一次尝试
                    lastError = wrap(reconnectExc);
                    continue;
                }
                try {
                    Object result = invoke(server, tool, arguments);
                    markRecovered(server);
                    audit("mcp_reconnected", Map.of("server", server, "attempt", attempt));
                    return result;
                } catch (McpException retryExc) {
                    lastError = retryExc;
                    if (!isConnectionError(retryExc)) {
                        // 重连成功但调用本身失败(如参数错误):服务已恢复,原样抛出
                        markRecovered(server);
                        throw retryExc;
                    }
                }
            }
            trip(server, lastError);
            throw new McpCircuitOpenException("MCP 服务 " + server + " 暂时不可用(重连 "
                + attempts + " 次失败,已熔断,约 " + (int) Math.ceil(circuitCooldownSeconds)
                + " 秒后自动重试),请稍后重试");
        } finally {
            stateLock.lock();
            try {
                circuits.get(server).recovering = false;
            } finally {
                stateLock.unlock();
            }
        }
    }

    private void trip(String server, McpException error) {
        stateLock.lock();
        try {
            CircuitState circuit = circuits.computeIfAbsent(server, k -> new CircuitState());
            circuit.phase = CircuitState.Phase.OPEN;
            circuit.openedAt = mono();
            circuit.recovering = false;
        } finally {
            stateLock.unlock();
        }
        audit("mcp_circuit_open", Map.of("server", server,
            "cooldown", circuitCooldownSeconds, "error", String.valueOf(error.getMessage())));
    }

    private void markRecovered(String server) {
        stateLock.lock();
        try {
            CircuitState circuit = circuits.computeIfAbsent(server, k -> new CircuitState());
            circuit.phase = CircuitState.Phase.CLOSED;
            circuit.openedAt = 0;
            circuit.recovering = false;
        } finally {
            stateLock.unlock();
        }
        audit("mcp_circuit_closed", Map.of("server", server));
    }

    /** 断开已损坏的连接并按原始参数重建;重连成功即刷新快照(工具目录可能变化)。 */
    private void reconnect(String server) {
        ServerSpec spec;
        Connection old;
        stateLock.lock();
        try {
            spec = specs.get(server);
            old = connections.remove(server);
        } finally {
            stateLock.unlock();
        }
        if (spec == null) {
            throw new McpException("MCP server has no reconnect spec: " + server);
        }
        if (old != null) {
            try {
                factory().close(old.client());
            } catch (Exception ignored) {
                // 关闭已损坏的连接不得阻断重连
            }
            audit("mcp_disconnected", Map.of("server", server));
        }
        McpSyncClient client = factory().connect(spec.url());
        McpServerSnapshot snapshot = discover(server, "streamable-http", client);
        stateLock.lock();
        try {
            connections.put(server, new Connection(client, snapshot));
            snapshots.put(server, snapshot);
        } finally {
            stateLock.unlock();
        }
    }

    /** 单次远端调用:未知工具拒绝;isError → McpException;structured 优先。 */
    private Object invoke(String server, String tool, Map<String, Object> arguments) {
        Connection connection;
        stateLock.lock();
        try {
            connection = connections.get(server);
        } finally {
            stateLock.unlock();
        }
        if (connection == null) {
            throw new McpException("MCP server is not connected: " + server);
        }
        boolean known = connection.snapshot().tools().stream()
            .anyMatch(info -> info.name().equals(tool));
        if (!known) {
            throw new McpException("MCP tool is not advertised by " + server + ": " + tool);
        }
        CallToolResult result;
        try {
            result = connection.client().callTool(new CallToolRequest(tool, arguments));
        } catch (Exception e) {
            throw wrap(e);
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw new McpException(contentText(result).isEmpty()
                ? "MCP tool failed: " + tool : contentText(result));
        }
        if (result.structuredContent() != null) {
            return result.structuredContent();
        }
        String text = contentText(result);
        if (text.isEmpty()) {
            return Map.of("content", result.content());
        }
        try {
            return Json.readTree(text);
        } catch (RuntimeException e) {
            return Map.of("text", text);
        }
    }

    private static String contentText(CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        return result.content().stream()
            .filter(c -> c instanceof TextContent)
            .map(c -> ((TextContent) c).text())
            .reduce((a, b) -> a + "\n" + b)
            .map(String::strip).orElse("");
    }

    /**
     * 沿显式因果链(getCause)判定连接类失败;suppressed 不参与 —— 隐式上下文
     * 会把无关错误误分类为连接类(实测曾因此触发多余重连)。
     */
    static boolean isConnectionError(Throwable exc) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        java.util.Deque<Throwable> stack = new java.util.ArrayDeque<>();
        stack.push(exc);
        while (!stack.isEmpty()) {
            Throwable current = stack.pop();
            if (!seen.add(current)) {
                continue;
            }
            for (Class<? extends Throwable> type : CONNECTION_TYPES) {
                if (type.isInstance(current)) {
                    // McpError 的关键词判定(业务级 JSONRPC error 不是连接类)
                    if (type == io.modelcontextprotocol.spec.McpError.class) {
                        return hintsMatch(current);
                    }
                    return true;
                }
            }
            String text = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (CONNECTION_HINTS.stream().anyMatch(text::contains)) {
                return true;
            }
            if (current.getCause() != null && current.getCause() != current) {
                stack.push(current.getCause());
            }
        }
        return false;
    }

    private static boolean hintsMatch(Throwable t) {
        String text = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return CONNECTION_HINTS.stream().anyMatch(text::contains)
            || t.getCause() != null && isConnectionError(t.getCause());
    }

    private static McpException wrap(Exception e) {
        return e instanceof McpException mcp
            ? mcp : new McpException(String.valueOf(e.getMessage()), e);
    }

    private void validateNewName(String name) {
        if (!SERVER_NAME.matcher(name).matches()) {
            throw new McpException("invalid MCP server name: " + name);
        }
        stateLock.lock();
        try {
            if (snapshots.containsKey(name)) {
                throw new McpException("MCP server is already connected: " + name);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private McpServerSnapshot snapshot(String name) {
        stateLock.lock();
        try {
            McpServerSnapshot snapshot = snapshots.get(name);
            if (snapshot == null) {
                throw new McpException("MCP server is not connected: " + name);
            }
            return snapshot;
        } finally {
            stateLock.unlock();
        }
    }

    private void audit(String event, Map<String, Object> data) {
        if (auditHook == null) {
            return;
        }
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("event", event);
            record.putAll(data);
            auditHook.accept(record);
        } catch (RuntimeException ignored) {
            // Observability must not break an MCP call.
        }
    }

    private static double mono() {
        return System.nanoTime() / 1e9;
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP reconnect interrupted");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        List<Connection> toClose;
        stateLock.lock();
        try {
            toClose = new ArrayList<>(connections.values());
            connections.clear();
            snapshots.clear();
            specs.clear();
            circuits.clear();
        } finally {
            stateLock.unlock();
        }
        for (Connection connection : toClose) {
            try {
                factory().close(connection.client());
            } catch (RuntimeException ignored) {
                // 关闭失败不阻断其余连接
            }
        }
        closed = true;
    }
}
