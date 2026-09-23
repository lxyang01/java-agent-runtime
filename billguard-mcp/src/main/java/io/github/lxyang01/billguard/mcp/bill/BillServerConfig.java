package io.github.lxyang01.billguard.mcp.bill;

import io.github.lxyang01.billguard.bills.BillFilters;
import io.github.lxyang01.billguard.bills.BillPii;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
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
 * bill MCP server(:8010)。六工具/三资源/两 prompt 的描述与 meta 逐字对齐
 * bill_server.py;owner 是服务端注入的身份参数(host 未注入时空串 = 仅存量 NULL 行)。
 */
@Configuration
@org.springframework.context.annotation.Profile("bill")
public class BillServerConfig {

    public static String instructions() {
        return "提供个人账单的确定性查询、聚合、周期对比、异常检测和脱敏样本。"
            + "统计结果来自 PostgreSQL;样本备注始终经过 PII 脱敏。";
    }

    private static final ToolAnnotations READ_ONLY =
        new ToolAnnotations(null, true, false, true, false, null);
    private static final ToolAnnotations WRITE =
        new ToolAnnotations(null, false, false, false, false, null);

    private final BillRepository bills;
    private final BillAnomalies anomalies;

    public BillServerConfig(BillRepository bills, BillAnomalies anomalies) {
        this.bills = bills;
        this.anomalies = anomalies;
    }

    /** scoped_or_legacy:空 owner → 仅存量 NULL 行;非空 → 播种默认类别 + 本人视图。 */
    private String scope(Map<String, Object> arguments) {
        Object owner = arguments.get("owner");
        String value = owner == null ? "" : String.valueOf(owner);
        if (!value.isEmpty()) {
            bills.ensureUserCategories(value);
        }
        return value;
    }

    @Bean
    public WebMvcStatelessServerTransport billTransport() {
        return WebMvcStatelessServerTransport.builder().messageEndpoint("/mcp").build();
    }

    @Bean
    public RouterFunction<ServerResponse> billMcpRouter(WebMvcStatelessServerTransport t) {
        return t.getRouterFunction();
    }

    @Bean
    public McpStatelessSyncServer billMcpServer(WebMvcStatelessServerTransport transport) {
        return McpServer.sync(transport)
            .serverInfo("BillGuard Data MCP", "1.0.0")
            .instructions(instructions())
            .tools(
                tool("aggregate", READ_ONLY, null,
                    "聚合账单总金额、笔数、待核查数量、日均支出、类别分布和高频商户。",
                    filterProps(),
                    (ctx, request) -> structured(bills.overview(
                        filters(request.arguments()), scope(request.arguments())))),
                tool("query", READ_ONLY, null,
                    "按时间、类别、商户、金额区间或关键词查询最多 50 条脱敏交易;关键词同时匹配交易编号、商户、备注和类别。",
                    withLimit(filterProps(), 1, 50, 20),
                    (ctx, request) -> {
                        Map<String, Object> result = bills.query(
                            filters(request.arguments()), 1,
                            clamp(optInt(request.arguments(), "limit", 20), 1, 50),
                            scope(request.arguments()));
                        return structured(masked(result));
                    }),
                tool("compare_periods", READ_ONLY, null,
                    "将最近 N 天支出与此前等长周期进行确定性比较。",
                    props("days", integer(1, 365, 7)),
                    (ctx, request) -> {
                        int days = optInt(request.arguments(), "days", 7);
                        requireRange("days", days, 1, 365);
                        return structured(bills.compare(days, scope(request.arguments())));
                    }),
                tool("detect_anomalies", READ_ONLY, null,
                    "识别四类账单异常:类别激增、疑似重复扣费、订阅涨价和大额离群。",
                    props("days", integer(1, 365, 7),
                        "dimension", stringEnum("spike", "duplicate", "price_hike", "outlier"),
                        "limit", integer(1, 50, 10)),
                    (ctx, request) -> {
                        int days = optInt(request.arguments(), "days", 7);
                        requireRange("days", days, 1, 365);
                        int limit = optInt(request.arguments(), "limit", 10);
                        requireRange("limit", limit, 1, 50);
                        return structured(anomalies.anomalies(days,
                            optString(request.arguments(), "dimension", "spike"), limit,
                            scope(request.arguments())));
                    }),
                tool("get_samples", READ_ONLY, null,
                    "读取最多 20 条已脱敏代表性交易;具体问题优先传 merchant,其次 category,零结果时按 retry_hint 放宽一次查询。",
                    props("merchant", Map.of("type", "string"),
                        "category", Map.of("type", "string"),
                        "query", Map.of("type", "string"),
                        "limit", integer(1, 20, 10),
                        "date_from", Map.of("type", "string"),
                        "date_to", Map.of("type", "string")),
                    (ctx, request) -> {
                        int limit = optInt(request.arguments(), "limit", 10);
                        requireRange("limit", limit, 1, 20);
                        return structured(samples(request.arguments()));
                    }),
                tool("update_status", WRITE,
                    Map.of("risk_level", "high_write", "requires_approval", true,
                        "policy_reason", "Updates bill transaction workflow state"),
                    "更新交易核查状态。这是写操作,MCP Host 必须在调用前获得用户批准。",
                    props("tx_ids", Map.of("type", "array", "items", Map.of("type", "string")),
                        "status", stringEnum(BillRepository.WORKFLOW_STATUSES.toArray(String[]::new)),
                        "operator", Map.of("type", "string"),
                        "note", Map.of("type", "string")),
                    (ctx, request) -> {
                        List<String> txIds = stringList(request.arguments().get("tx_ids"));
                        if (txIds == null || txIds.isEmpty() || txIds.size() > 100) {
                            throw new IllegalArgumentException("tx_ids 必须包含 1 到 100 个交易编号");
                        }
                        String operator = optString(request.arguments(), "operator");
                        if (operator == null) {
                            throw new IllegalArgumentException("operator 不能为空");
                        }
                        return structured(bills.updateWorkflow(txIds, operator.strip(),
                            optString(request.arguments(), "status", "正常"),
                            optString(request.arguments(), "note", ""),
                            scope(request.arguments())));
                    }))
            .resources(
                new SyncResourceSpecification(
                    new Resource("bill://schema", "bill-schema", null,
                        "账单交易字段、类型和隐私约束。", "application/json", null, null),
                    (ctx, request) -> text("bill://schema", SCHEMA_JSON)),
                new SyncResourceSpecification(
                    new Resource("bill://categories", "bill-categories", null,
                        "账单类目目录:名称、关键词与启用位(静态、与 owner 无关)。", "application/json",
                        null, null),
                    (ctx, request) -> text("bill://categories", CATEGORIES_JSON)),
                new SyncResourceSpecification(
                    new Resource("bill://metric-definitions", "metric-definitions", null,
                        "四类异常调查使用的指标定义和阈值。", "application/json", null, null),
                    (ctx, request) -> text("bill://metric-definitions", METRICS_JSON)))
            .prompts(
                new SyncPromptSpecification(
                    new Prompt("investigate-bill-anomaly", "生成账单异常调查任务模板。",
                        (List<io.modelcontextprotocol.spec.McpSchema.PromptArgument>) null),
                    (ctx, request) -> prompt(
                        "调查最近 " + arg(request, "days", "7") + " 天账单在 "
                            + arg(request, "dimension", "spike")
                            + " 维度的异常。先对比等长周期确认口径,再核对四类阈值定义,"
                            + "最后读取脱敏样本取证。输出数据事实、原因假设、置信度和下一步验证动作;"
                            + "未经证实不得写已确认根因。")),
                new SyncPromptSpecification(
                    new Prompt("monthly-guard-report", "生成月度守卫报告任务模板。",
                        (List<io.modelcontextprotocol.spec.McpSchema.PromptArgument>) null),
                    (ctx, request) -> prompt(
                        "生成最近 30 天的月度守卫报告,对比此前 30 天。"
                            + "包括支出事实、异常清单(四类维度)、根因推测、行动计划和数据限制;"
                            + "取消订阅、退款等行动必须走工单审批,不得宣称已执行。")))
            .build();
    }

    // ---- 静态资源文本(逐字对齐 bill_server.py) ----

    private static final String SCHEMA_JSON = """
        {"fields": {"tx_id": "string, unique business identifier", \
        "paid_at": "ISO date-time", "merchant": "string", "category": "string", \
        "amount": "number, CNY yuan", "method": "string", \
        "note": "string, PII masked when exposed through MCP", \
        "status": "%s"}, \
        "privacy": "MCP query and sample tools return masked notes only"}"""
        .formatted(String.join(" | ", BillRepository.WORKFLOW_STATUSES));

    private static final String CATEGORIES_JSON = """
        {"categories": [%s]}""".formatted(
        BillRepository.DEFAULT_CATEGORIES.stream()
            .map(entry -> "{\"name\": \"" + entry[0] + "\", \"keywords\": ["
                + java.util.Arrays.stream(entry[1].split(",")).map(k -> "\"" + k.strip() + "\"")
                    .collect(java.util.stream.Collectors.joining(", "))
                + "], \"enabled\": true}")
            .collect(java.util.stream.Collectors.joining(", ")));

    private static final String METRICS_JSON = """
        {"compare": {"change_percent": \
        "(current_total - previous_total) / previous_total * 100", \
        "empty_previous": "previous_total is zero: change is undefined, report amounts only"}, \
        "dimensions": {"spike": {"definition": \
        "category current-period amount >= spike_ratio x previous and >= spike_min", \
        "spike_ratio": 2, "spike_min": 100}, \
        "duplicate": {"definition": \
        "same merchant and amount charged again within duplicate_window_days", \
        "duplicate_window_days": 3}, \
        "price_hike": {"definition": \
        "subscription latest charge differs from expected_amount by max(hike_min_abs, hike_ratio x expected)", \
        "hike_min_abs": 1, "hike_ratio": 0.2}, \
        "outlier": {"definition": \
        "single charge >= outlier_min and >= outlier_ratio x its category mean", \
        "outlier_min": 200, "outlier_ratio": 5}}, \
        "privacy": "anomaly items never expose raw notes; use get_samples for masked evidence"}""";

    // ---- 工具辅助 ----

    /** samples:检索优先级 商户精确 > 类别 > 关键词(对齐 bills.py samples)。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> samples(Map<String, Object> arguments) {
        String owner = scope(arguments);
        String merchant = optString(arguments, "merchant");
        String category = optString(arguments, "category");
        BillFilters filters = merchant != null
            ? new BillFilters(optString(arguments, "date_from", ""),
                optString(arguments, "date_to", ""), "", merchant, "", "", null, null, "")
            : category != null
                ? new BillFilters(optString(arguments, "date_from", ""),
                    optString(arguments, "date_to", ""), category, "", "", "", null, null, "")
                : new BillFilters(optString(arguments, "date_from", ""),
                    optString(arguments, "date_to", ""), "", "", "", "", null, null,
                    optString(arguments, "query", ""));
        Map<String, Object> result = bills.query(filters, 1,
            clamp(optInt(arguments, "limit", 10), 1, 20), owner);
        List<Map<String, Object>> samples = new java.util.ArrayList<>();
        for (Object itemObj : (List<?>) result.get("items")) {
            Map<String, Object> item = (Map<String, Object>) itemObj;
            Map<String, Object> sample = new LinkedHashMap<>();
            for (String key : List.of("tx_id", "paid_at", "merchant", "category", "amount",
                "note")) {
                sample.put(key, item.get(key));
            }
            samples.add(sample);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("samples", samples);
        response.put("matched", result.get("total"));
        response.put("pii_masked", true);
        if (result.get("retry_hint") != null) {
            response.put("retry_hint", result.get("retry_hint"));
        }
        return response;
    }

    private static Map<String, Object> masked(Map<String, Object> result) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Object itemObj : (List<?>) result.get("items")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) itemObj);
            item.put("note", BillPii.mask(String.valueOf(item.getOrDefault("note", ""))).text());
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("items", items);
        response.put("pii_masked", true);
        return response;
    }

    private static BillFilters filters(Map<String, Object> arguments) {
        return new BillFilters(optString(arguments, "date_from", ""),
            optString(arguments, "date_to", ""), optString(arguments, "category", ""),
            optString(arguments, "merchant", ""), optString(arguments, "method", ""),
            optString(arguments, "status", ""),
            arguments.get("min_amount") instanceof Number n ? n.doubleValue() : null,
            arguments.get("max_amount") instanceof Number n ? n.doubleValue() : null,
            optString(arguments, "query", ""));
    }

    // ---- 构造小件(与 WorkItemServerConfig 同构) ----

    private static SyncToolSpecification tool(String name, ToolAnnotations annotations,
        Map<String, Object> meta, String description, Map<String, Object> properties,
        BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder()
            .tool(Tool.builder().name(name).description(description)
                .inputSchema(schema(properties)).annotations(annotations).meta(meta).build())
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

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /** 筛选参数 + owner(身份由 host 注入,空串=仅存量 NULL 行)。 */
    private static Map<String, Object> filterProps() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date_from", Map.of("type", "string"));
        map.put("date_to", Map.of("type", "string"));
        map.put("category", Map.of("type", "string"));
        map.put("merchant", Map.of("type", "string"));
        map.put("method", Map.of("type", "string"));
        map.put("status", Map.of("type", "string"));
        map.put("min_amount", Map.of("type", "number"));
        map.put("max_amount", Map.of("type", "number"));
        map.put("query", Map.of("type", "string"));
        return map;
    }

    private static Map<String, Object> withLimit(Map<String, Object> base, int min, int max,
                                                 int def) {
        Map<String, Object> map = new LinkedHashMap<>(base);
        map.put("limit", integer(min, max, def));
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

    private static ReadResourceResult text(String uri, String json) {
        return new ReadResourceResult(List.of(
            new TextResourceContents(uri, json, "application/json")));
    }

    private static GetPromptResult prompt(String text) {
        return new GetPromptResult(null, List.of(
            new PromptMessage(Role.USER, new TextContent(text))));
    }

    private static String arg(GetPromptRequest request, String key, String def) {
        if (request.arguments() == null) {
            return def;
        }
        Object value = request.arguments().get(key);
        return value == null ? def : String.valueOf(value);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 " + min + " 到 " + max + " 之间");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
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
