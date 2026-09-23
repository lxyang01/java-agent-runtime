package io.github.lxyang01.billguard.bills;

import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账单五工具注册表(描述与 schema 逐字对齐 agents/bills.py)。
 * owner 在装配期闭包绑定 —— 模型不可见、不可伪造(与 for_user 同构)。
 */
public final class BillTools {

    private BillTools() {}

    public static ToolRegistry build(BillRepository bills, BillAnomalies anomalies,
                                     String owner) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolDefinition("bill_overview",
            "查询账单总金额、笔数、待核查数量、日均支出、类别分布和高频商户。回答总体支出情况前必须调用。",
            object(filterProperties(), List.of()),
            io.github.lxyang01.agent.policy.ToolPolicy.read(),
            args -> bills.overview(filtersFrom(args), owner), null));

        registry.register(new ToolDefinition("bill_compare",
            "比较最近一段时间与上一相同长度周期的支出金额和类别结构。",
            object(Map.of("days", Map.of("type", "integer", "minimum", 1)), List.of()),
            io.github.lxyang01.agent.policy.ToolPolicy.read(),
            args -> bills.compare(intArg(args, "days", 7), owner), null));

        registry.register(new ToolDefinition("bill_anomalies",
            "检测最近周期的支出异常：spike 类别激增、duplicate 疑似重复扣费、price_hike 订阅涨价、outlier 大额离群。"
                + "用于回答异常、涨价、重复扣费和盗刷问题。",
            object(Map.of(
                "days", Map.of("type", "integer", "minimum", 1),
                "dimension", Map.of("type", "string", "enum",
                    List.of("spike", "duplicate", "price_hike", "outlier")),
                "limit", Map.of("type", "integer", "minimum", 1)),
                List.of()),   // _object 缺省 required=[],参数全有默认值
            io.github.lxyang01.agent.policy.ToolPolicy.read(),
            args -> anomalies.anomalies(intArg(args, "days", 7),
                strArg(args, "dimension", "spike"), intArg(args, "limit", 10), owner), null));

        registry.register(new ToolDefinition("bill_search",
            "按筛选条件查询交易明细，返回有限数量的脱敏记录。query 会同时匹配交易编号、商户、备注和类别；"
                + "用于定位具体交易，不用于大批量总结。",
            object(merge(filterProperties(),
                Map.of("limit", Map.of("type", "integer", "minimum", 1, "maximum", 50))),
                List.of()),
            io.github.lxyang01.agent.policy.ToolPolicy.read(),
            args -> bills.query(filtersFrom(args), 1, Math.min(intArg(args, "limit", 20), 50),
                owner), null));

        registry.register(new ToolDefinition("bill_samples",
            "读取最多 20 条已脱敏的代表性交易，用于分析某个商户、类别或关键词背后的可能原因。",
            object(Map.of(
                "merchant", Map.of("type", "string"),
                "category", Map.of("type", "string"),
                "query", Map.of("type", "string"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20),
                "date_from", Map.of("type", "string"),
                "date_to", Map.of("type", "string")), List.of()),
            io.github.lxyang01.agent.policy.ToolPolicy.read(),
            args -> samples(bills, args, owner), null));
        return registry;
    }

    /** samples:检索优先级 商户精确 > 类别 > 关键词(对齐 bills.py samples)。 */
    private static Map<String, Object> samples(BillRepository bills, Map<String, Object> args,
                                               String owner) {
        String merchant = strArg(args, "merchant", "");
        String category = strArg(args, "category", "");
        BillFilters filters = !merchant.isEmpty()
            ? new BillFilters(strArg(args, "date_from", ""), strArg(args, "date_to", ""), "",
                merchant, "", "", null, null, "")
            : !category.isEmpty()
                ? new BillFilters(strArg(args, "date_from", ""), strArg(args, "date_to", ""),
                    category, "", "", "", null, null, "")
                : new BillFilters(strArg(args, "date_from", ""), strArg(args, "date_to", ""), "",
                    "", "", "", null, null, strArg(args, "query", ""));
        Map<String, Object> result = bills.query(filters, 1,
            Math.min(Math.max(1, intArg(args, "limit", 10)), 20), owner);
        List<Map<String, Object>> samples = new java.util.ArrayList<>();
        for (Object itemObj : (List<?>) result.get("items")) {
            Map<?, ?> item = (Map<?, ?>) itemObj;
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

    // ---- schema/参数小工具 ----

    private static Map<String, Object> filterProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("date_from", Map.of("type", "string", "description",
            "开始日期，ISO 日期或时间"));
        properties.put("date_to", Map.of("type", "string", "description",
            "结束日期，ISO 日期或时间"));
        properties.put("category", Map.of("type", "string"));
        properties.put("merchant", Map.of("type", "string"));
        properties.put("method", Map.of("type", "string"));
        properties.put("status", Map.of("type", "string"));
        properties.put("min_amount", Map.of("type", "number"));
        properties.put("max_amount", Map.of("type", "number"));
        properties.put("query", Map.of("type", "string", "description",
            "交易编号、商户、备注或类别关键词"));
        return properties;
    }

    private static Map<String, Object> merge(Map<String, Object> base,
                                             Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    private static Map<String, Object> object(Map<String, Object> properties,
                                              List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static BillFilters filtersFrom(Map<String, Object> args) {
        return new BillFilters(strArg(args, "date_from", ""), strArg(args, "date_to", ""),
            strArg(args, "category", ""), strArg(args, "merchant", ""),
            strArg(args, "method", ""), strArg(args, "status", ""),
            args.get("min_amount") instanceof Number n ? n.doubleValue() : null,
            args.get("max_amount") instanceof Number n ? n.doubleValue() : null,
            strArg(args, "query", ""));
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        return args.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    private static String strArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
