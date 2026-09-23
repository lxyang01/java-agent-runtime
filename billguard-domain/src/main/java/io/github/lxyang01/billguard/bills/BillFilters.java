package io.github.lxyang01.billguard.bills;

import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.util.Set;

/** 交易筛选条件(字段与 BillFilters 一致)。 */
public record BillFilters(
    String dateFrom,
    String dateTo,
    String category,
    String merchant,
    String method,
    String status,
    BigDecimal minAmount,
    BigDecimal maxAmount,
    String query) {

    public static final BillFilters EMPTY = new BillFilters("", "", "", "", "", "", null,
        null, "");

    private static final Set<String> FIELDS = Set.of("date_from", "date_to", "category",
        "merchant", "method", "status", "min_amount", "max_amount", "query");

    /** 从请求体提取(支持 body.filters 或平铺;忽略空值),对齐 web.py _filters。 */
    public static BillFilters fromBody(Map<String, Object> body) {
        Map<String, Object> source = body.get("filters") instanceof Map<?, ?> nested
            ? cast(nested) : body;
        Map<String, Object> picked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (FIELDS.contains(entry.getKey())
                && entry.getValue() != null && !"".equals(entry.getValue())) {
                picked.put(entry.getKey(), entry.getValue());
            }
        }
        return new BillFilters(
            text(picked.get("date_from")), text(picked.get("date_to")),
            text(picked.get("category")), text(picked.get("merchant")),
            text(picked.get("method")), text(picked.get("status")),
            picked.get("min_amount") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null,
            picked.get("max_amount") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null,
            text(picked.get("query")));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
