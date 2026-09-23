package io.github.lxyang01.billguard.chat;

import io.github.lxyang01.agent.types.RunEvent;
import io.github.lxyang01.agent.types.RunEvents;
import io.github.lxyang01.agent.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * tool_end 事件 → 前端可点的证据条目(逐字对齐 web.py _build_evidence):
 * 总览/聚合→类别卡片;对比→周期卡片;查询/样本→商户样本;按 filters 去重取前 12。
 */
public final class EvidenceBuilder {

    private EvidenceBuilder() {}

    public static List<Map<String, Object>> build(List<RunEvent> events) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (RunEvent event : events) {
            if (!RunEvents.TOOL_END.equals(event.eventType())) {
                continue;
            }
            String tool = String.valueOf(event.data().getOrDefault("tool", ""));
            String leaf = tool.contains(".")
                ? tool.substring(tool.lastIndexOf('.') + 1) : tool;
            if (!(event.data().get("result") instanceof Map<?, ?> resultMap)) {
                continue;
            }
            Map<String, Object> result = asObjectMap(resultMap);
            if ("bill_overview".equals(tool) || "aggregate".equals(leaf)) {
                for (Object itemObj : limitList(listOrEmpty(result.get("by_category")), 8)) {
                    Map<String, Object> item = asObjectMap(itemObj);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("label", item.getOrDefault("name", "类别"));
                    entry.put("description", "¥" + gFormat(item.get("amount")) + " · "
                        + gFormat(item.get("count")) + " 笔");
                    entry.put("filters", Map.of("category",
                        String.valueOf(item.getOrDefault("name", ""))));
                    evidence.add(entry);
                }
            } else if ("bill_compare".equals(tool) || "compare_periods".equals(leaf)) {
                Map<String, Object> period = asObjectMap(result.get("current_period"));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("label", "查看本周期支出");
                entry.put("description", "¥" + gFormat(period.get("total")));
                Map<String, Object> filters = new LinkedHashMap<>();
                filters.put("date_from", String.valueOf(period.getOrDefault("from", "")));
                filters.put("date_to", String.valueOf(period.getOrDefault("to", "")));
                entry.put("filters", filters);
                evidence.add(entry);
            } else if ("bill_search".equals(tool) || "bill_samples".equals(tool)
                || "query".equals(leaf) || "get_samples".equals(leaf)) {
                List<?> source = result.get("samples") instanceof List<?> samples
                    ? samples : listOrEmpty(result.get("items"));
                for (Object itemObj : limitList(source, 10)) {
                    Map<String, Object> item = asObjectMap(itemObj);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("label", item.getOrDefault("merchant", "查看交易"));
                    entry.put("description", "¥" + gFormat(item.get("amount")));
                    entry.put("filters", Map.of("merchant",
                        String.valueOf(item.getOrDefault("merchant", ""))));
                    evidence.add(entry);
                }
            }
        }
        // filters 去重,保序取前 12
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> item : evidence) {
            Object filters = item.get("filters");
            Map<String, Object> sorted = new LinkedHashMap<>();
            if (filters instanceof Map<?, ?> filterMap) {
                new java.util.TreeMap<>(asObjectMap(filterMap))
                    .forEach((key, value) -> sorted.put(key, value));
            }
            String key = Json.write(sorted);
            if (seen.add(key)) {
                unique.add(item);
            }
        }
        return unique.subList(0, Math.min(12, unique.size()));
    }

    private static List<?> listOrEmpty(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static List<?> limitList(List<?> list, int max) {
        return list.subList(0, Math.min(max, list.size()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /** %g 风格数字渲染。 */
    private static String gFormat(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
