package io.github.lxyang01.billguard.mcp.workitem;

import io.github.lxyang01.billguard.storage.PgWorkItemStore;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * work-item MCP server(:8020)。工具目录/描述/annotations/meta 逐字对齐
 * work_item_server.py;approve 刻意不作为工具暴露(通道外人工决策)。
 */
@Configuration
@org.springframework.context.annotation.Profile("work-item")
public class WorkItemServerConfig {

    public static final String INSTRUCTIONS =
        "管理客户反馈行动项。创建工单采用 prepare/approve/commit 三阶段协议;"
            + "approve 只能由 MCP 通道外的人类操作完成。";

    private static final ToolAnnotations READ_ONLY =
        new ToolAnnotations(null, true, false, true, false, null);
    private static final ToolAnnotations PREPARE_WRITE =
        new ToolAnnotations(null, false, false, false, false, null);
    private static final ToolAnnotations COMMIT_WRITE =
        new ToolAnnotations(null, false, false, true, false, null);

    private static final String SCHEMA_JSON = """
        {"issue": {"id": "ISS-NNNN", \
        "priority": ["low", "medium", "high", "urgent"], \
        "status": ["open", "in_progress", "done"]}, \
        "approval_flow": ["pending", "approved_or_rejected", "consumed"], \
        "rule": "approve is deliberately not exposed as an MCP tool"}""";

    private final PgWorkItemStore store;

    public WorkItemServerConfig(PgWorkItemStore store) {
        this.store = store;
    }

    @Bean
    public WebMvcStatelessServerTransport workItemTransport() {
        return WebMvcStatelessServerTransport.builder().messageEndpoint("/mcp").build();
    }

    @Bean
    public RouterFunction<ServerResponse> workItemMcpRouter(WebMvcStatelessServerTransport t) {
        return t.getRouterFunction();
    }

    @Bean
    public McpStatelessSyncServer workItemMcpServer(WebMvcStatelessServerTransport transport) {
        return McpServer.sync(transport)
            .serverInfo("Work Item MCP", "1.0.0")
            .instructions(INSTRUCTIONS)
            .tools(
                tool("list_issues", READ_ONLY, null, "列出已创建的行动项。",
                    props("status", stringEnum("open", "in_progress", "done"),
                        "limit", integer(1, 200, 50)),
                    (ctx, request) -> structured(store.listIssues(
                        optString(request.arguments(), "status"),
                        optInt(request.arguments(), "limit", 50)))),
                tool("get_issue", READ_ONLY, null, "按 ID 获取一个行动项。",
                    props("issue_id", Map.of("type", "string")),
                    (ctx, request) -> structured(store.getIssue(
                        requireString(request.arguments(), "issue_id")))),
                tool("prepare_issue", PREPARE_WRITE,
                    Map.of("risk_level", "low_write", "requires_approval", false,
                        "policy_reason", "Creates only an expiring approval request"),
                    "准备创建行动项并返回审批 ID;此步骤不会创建正式工单。",
                    props("title", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "priority", stringEnum("low", "medium", "high", "urgent"),
                        "evidence_refs", Map.of("type", "array", "items",
                            Map.of("type", "string"))),
                    (ctx, request) -> structured(store.prepareIssue(
                        requireString(request.arguments(), "title"),
                        requireString(request.arguments(), "description"),
                        optString(request.arguments(), "priority", "medium"),
                        stringList(request.arguments().get("evidence_refs"))))),
                tool("commit_issue", COMMIT_WRITE,
                    Map.of("risk_level", "high_write", "requires_approval", true,
                        "policy_reason", "Creates a durable external work item"),
                    "提交已经由通道外人类批准的行动项;未批准请求必定失败。",
                    props("approval_id", Map.of("type", "string")),
                    (ctx, request) -> structured(store.commitIssue(
                        requireString(request.arguments(), "approval_id")))))
            .resources(new SyncResourceSpecification(
                new Resource("work-items://schema", "work-item-schema", null,
                    "工单字段及审批状态机。", "application/json", null, null),
                (ctx, request) -> new ReadResourceResult(List.of(
                    new TextResourceContents("work-items://schema", SCHEMA_JSON,
                        "application/json")))))
            .prompts(new SyncPromptSpecification(
                new Prompt("create-bill-action-item",
                    "生成从账单守卫发现创建行动项的受控任务模板。",
                    (List<io.modelcontextprotocol.spec.McpSchema.PromptArgument>) null),
                (ctx, request) -> new GetPromptResult(null, List.of(
                    new PromptMessage(Role.USER, new TextContent(promptText(request)))))))
            .build();
    }

    private static String promptText(GetPromptRequest request) {
        Map<String, Object> args =
            request.arguments() == null ? Map.of() : request.arguments();
        String problem = String.valueOf(args.getOrDefault("problem", ""));
        String evidenceRefs = String.valueOf(args.getOrDefault("evidence_refs", ""));
        return "为以下账单问题准备行动项:" + problem + "。"
            + "证据引用:" + (evidenceRefs.isEmpty() ? "未提供" : evidenceRefs) + "。"
            + "先调用 prepare_issue 取得完整参数和 approval_id,再用该 ID 提出 commit_issue 调用。"
            + "Host 必须在调用到达本服务前暂停并请求人类审批;拿到成功结果前不得声称工单已创建。";
    }

    // ---- 构造小件 ----

    private static SyncToolSpecification tool(String name, ToolAnnotations annotations,
        Map<String, Object> meta, String description, Map<String, Object> properties,
        BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder()
            .tool(Tool.builder().name(name).description(description)
                .inputSchema(schema(properties)).annotations(annotations).meta(meta).build())
            // FastMCP:业务异常 → isError=true 的工具结果(而非 JSONRPC error)
            .callHandler((ctx, request) -> {
                try {
                    return handler.apply(ctx, request);
                } catch (RuntimeException e) {
                    return new CallToolResult(e.getMessage(), true);
                }
            })
            .build();
    }

    private static JsonSchema schema(Map<String, Object> properties) {
        return new JsonSchema("object", properties, List.of(), Boolean.TRUE, null, null);
    }

    /** 有序 properties(LinkedHashMap 保参数序)。 */
    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> stringEnum(String... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "string");
        map.put("enum", List.of(values));
        return map;
    }

    private static Map<String, Object> integer(int minimum, int maximum, int def) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "integer");
        map.put("minimum", minimum);
        map.put("maximum", maximum);
        map.put("default", def);
        return map;
    }

    private static CallToolResult structured(Object value) {
        return new CallToolResult(List.of(), false, value, null);
    }

    private static String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " 不能为空");
        }
        return String.valueOf(value);
    }

    private static String optString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String optString(Map<String, Object> arguments, String key, String def) {
        String value = optString(arguments, key);
        return value == null ? def : value;
    }

    private static int optInt(Map<String, Object> arguments, String key, int def) {
        return arguments.get(key) instanceof Number number ? number.intValue() : def;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return list.stream().map(String::valueOf).toList();
    }
}
