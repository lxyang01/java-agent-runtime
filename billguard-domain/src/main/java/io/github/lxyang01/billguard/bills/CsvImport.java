package io.github.lxyang01.billguard.bills;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

/**
 * 账单/订阅 CSV 导入(字段别名映射、日期规整、逐行错误收集;对齐 bills.py)。
 * 表头别名支持中文;日期输出统一 "YYYY-MM-DD HH:mm:ss"(空格分隔)。
 */
public final class CsvImport {

    /** 字段别名(逐字对齐 FIELD_ALIASES)。 */
    private static final Map<String, List<String>> FIELD_ALIASES = Map.of(
        "tx_id", List.of("tx_id", "交易编号", "交易id", "交易_id", "订单号", "id"),
        "paid_at", List.of("paid_at", "时间", "交易时间", "支付时间", "created_at"),
        "merchant", List.of("merchant", "商户", "商户名称"),
        "category", List.of("category", "类别", "分类"),
        "amount", List.of("amount", "金额"),
        "method", List.of("method", "方式", "支付方式"),
        "note", List.of("note", "备注"));

    private static final Map<String, List<String>> SUBSCRIPTION_ALIASES = Map.of(
        "name", List.of("name", "订阅名称", "名称"),
        "merchant", List.of("merchant", "商户"),
        "cycle", List.of("cycle", "周期"),
        "expected_amount", List.of("expected_amount", "预期金额"));

    private static final List<DateTimeFormatter> DATE_PATTERNS = List.of(
        DateTimeFormatter.ofPattern("yyyy/M/d H:m:s"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:m"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
        DateTimeFormatter.ofPattern("yyyy-M-d"));

    private CsvImport() {}

    public record BillRow(String txId, String paidAt, String merchant, String note,
                          BigDecimal amount, String method, String categoryName) {}

    public record SubscriptionRow(String name, String merchant, String cycle,
                                  BigDecimal expectedAmount) {}

    public record ParseResult<T>(List<T> rows, int total, List<String> errors,
                                 List<String> headers) {

        boolean failed() {
            return !errors.isEmpty();
        }
    }

    public static ParseResult<BillRow> parseBills(String csvText) {
        List<String> headers = new ArrayList<>();
        List<BillRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
            Iterable<CSVRecord> records = format.parse(
                new StringReader(csvText.stripLeading().startsWith("﻿")
                    ? csvText.substring(1) : csvText));
            Map<String, Integer> headerMap = ((org.apache.commons.csv.CSVParser) records)
                .getHeaderMap();
            Map<String, String> columns = columnMap(headerMap.keySet().stream().toList(),
                FIELD_ALIASES, headers);
            Set<String> missing = missingRequired(columns, "tx_id", "paid_at", "merchant",
                "amount");
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("CSV 缺少必要字段：" + String.join(", ", missing));
            }
            for (CSVRecord record : records) {
                try {
                    String txId = value(record, columns, "tx_id");
                    String merchant = value(record, columns, "merchant");
                    if (txId.isEmpty() || merchant.isEmpty()) {
                        throw new IllegalArgumentException("交易编号或商户为空");
                    }
                    String paidAt = normalizeDatetime(value(record, columns, "paid_at"));
                    BigDecimal amount = new BigDecimal(value(record, columns, "amount").strip());
                    String method = columns.containsKey("method")
                        ? orDefault(value(record, columns, "method"), "未知") : "未知";
                    String note = columns.containsKey("note")
                        ? value(record, columns, "note") : "";
                    String category = columns.containsKey("category")
                        ? value(record, columns, "category") : "";
                    rows.add(new BillRow(txId, paidAt, merchant, note, amount, method,
                        category));
                } catch (RuntimeException e) {
                    errors.add("第 " + (record.getRecordNumber() + 1) + " 行：" + e.getMessage());
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("CSV 没有表头");
        }
        return new ParseResult<>(rows, rows.size() + errors.size(), errors, headers);
    }

    public static ParseResult<SubscriptionRow> parseSubscriptions(String csvText) {
        List<SubscriptionRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
            Iterable<CSVRecord> records = format.parse(
                new StringReader(csvText.stripLeading().startsWith("﻿")
                    ? csvText.substring(1) : csvText));
            Map<String, Integer> headerMap = ((org.apache.commons.csv.CSVParser) records)
                .getHeaderMap();
            Map<String, String> columns = columnMap(headerMap.keySet().stream().toList(),
                SUBSCRIPTION_ALIASES, new ArrayList<>());
            Set<String> missing = missingRequired(columns, "name", "merchant",
                "expected_amount");
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("CSV 缺少必要字段：" + String.join(", ", missing));
            }
            for (CSVRecord record : records) {
                try {
                    String name = value(record, columns, "name");
                    String merchant = value(record, columns, "merchant");
                    if (name.isEmpty() || merchant.isEmpty()) {
                        throw new IllegalArgumentException("订阅名称或商户为空");
                    }
                    BigDecimal expected = new BigDecimal(value(record, columns,
                        "expected_amount").strip());
                    String cycle = columns.containsKey("cycle")
                        ? orDefault(value(record, columns, "cycle"), "月") : "月";
                    if (!"月".equals(cycle) && !"年".equals(cycle)) {
                        throw new IllegalArgumentException("周期必须是 月 或 年：" + cycle);
                    }
                    rows.add(new SubscriptionRow(name, merchant, cycle, expected));
                } catch (RuntimeException e) {
                    errors.add("第 " + (record.getRecordNumber() + 1) + " 行：" + e.getMessage());
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("CSV 没有表头");
        }
        return new ParseResult<>(rows, rows.size() + errors.size(), errors, List.of());
    }

    /** 别名映射:原始表头 → 规范字段(大小写/空白不敏感)。 */
    private static Map<String, String> columnMap(List<String> headers,
                                                 Map<String, List<String>> aliases,
                                                 List<String> normalizedOut) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String header : headers) {
            normalized.put(normalizeHeader(header), header);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            for (String alias : entry.getValue()) {
                String actual = normalized.get(alias);
                if (actual != null) {
                    result.put(entry.getKey(), actual);
                    break;
                }
            }
        }
        normalizedOut.addAll(normalized.values());
        return result;
    }

    private static Set<String> missingRequired(Map<String, String> columns, String... fields) {
        Set<String> missing = new LinkedHashSet<>();
        for (String field : fields) {
            if (!columns.containsKey(field)) {
                missing.add(field);
            }
        }
        return missing;
    }

    private static String value(CSVRecord record, Map<String, String> columns, String field) {
        String header = columns.get(field);
        String value = header == null ? null : record.isSet(header) ? record.get(header) : null;
        return value == null ? "" : value.strip();
    }

    private static String orDefault(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private static String normalizeHeader(String header) {
        return header.strip().toLowerCase().replace(" ", "_");
    }

    /** 日期规整:ISO(含 T/Z/偏移)或斜杠格式 → "YYYY-MM-DD HH:mm:ss"。 */
    private static String normalizeDatetime(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("交易时间为空");
        }
        // 去掉时区/小数秒尾巴,统一分隔符后按 "日期 时间" 解析
        String core = text.replace('T', ' ');
        int cut = core.length();
        for (int i = 10; i < core.length(); i++) {
            char c = core.charAt(i);
            if (c == '+' || c == '.' || c == 'Z' || c == 'z'
                || (c == '-' && i > 10 && core.indexOf(':') > 0 && i > core.indexOf(':'))) {
                cut = Math.min(cut, i);
            }
        }
        core = core.substring(0, cut).strip();
        try {
            String[] parts = core.split(" ", 2);
            LocalDate date = LocalDate.parse(parts[0].replace('/', '-'));
            LocalDateTime parsed = parts.length == 2
                ? date.atTime(java.time.LocalTime.parse(padTime(parts[1])))
                : date.atStartOfDay();
            return parsed.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException ignored) {
            // 落入显式格式表
        }
        for (DateTimeFormatter pattern : DATE_PATTERNS) {
            try {
                if (pattern.toString().contains("H")) {
                    return LocalDateTime.parse(text, pattern)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
                return LocalDate.parse(text, pattern).atStartOfDay()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (RuntimeException ignoredPattern) {
                // 尝试下一格式
            }
        }
        throw new IllegalArgumentException("无法识别交易时间：" + text);
    }

    /** LocalTime 需要秒位:补齐 "8:5" → "08:05:00"。 */
    private static String padTime(String time) {
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int second = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return String.format("%02d:%02d:%02d", hour, minute, second);
    }
}
