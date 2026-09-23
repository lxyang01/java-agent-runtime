package io.github.lxyang01.billguard.storage;

import io.github.lxyang01.billguard.bills.OwnerScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 四类异常检测(阈值、周期锚点、detail 文案与排序逐字对齐 bills.py anomalies)。
 * 独立类:检测算法与仓储查询职责分离,共享 BillRepository 的 compare 与数值规整。
 */
public final class BillAnomalies {

    public static final int DUPLICATE_WINDOW_DAYS = 3;
    public static final java.math.BigDecimal SPIKE_RATIO = new java.math.BigDecimal("2");
    public static final java.math.BigDecimal SPIKE_MIN = new java.math.BigDecimal("100");
    public static final java.math.BigDecimal OUTLIER_MIN = new java.math.BigDecimal("200");
    public static final java.math.BigDecimal OUTLIER_RATIO = new java.math.BigDecimal("5");
    public static final java.math.BigDecimal HIKE_MIN_ABS = new java.math.BigDecimal("1");
    public static final java.math.BigDecimal HIKE_RATIO = new java.math.BigDecimal("0.2");

    private static final Set<String> DIMENSIONS =
        Set.of("spike", "duplicate", "price_hike", "outlier");

    private final JdbcTemplate jdbc;
    private final BillRepository bills;

    public BillAnomalies(JdbcTemplate jdbc, BillRepository bills) {
        this.jdbc = jdbc;
        this.bills = bills;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> anomalies(int days, String dimension, int limit, String owner) {
        int safeDays = Math.min(Math.max(1, days), 90);
        if (!DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("dimension 必须是 spike、duplicate、price_hike 或 outlier");
        }
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("duplicate_window_days", DUPLICATE_WINDOW_DAYS);
        thresholds.put("spike_ratio", SPIKE_RATIO);
        thresholds.put("spike_min", SPIKE_MIN);
        thresholds.put("outlier_min", OUTLIER_MIN);
        thresholds.put("outlier_ratio", OUTLIER_RATIO);
        thresholds.put("hike_min_abs", HIKE_MIN_ABS);
        thresholds.put("hike_ratio", HIKE_RATIO);

        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "owner");
        String latest = jdbc.queryForObject(
            "SELECT MAX(substr(paid_at, 1, 10)) FROM transactions" + ownerWhere.sql(),
            String.class, ownerWhere.params().toArray());
        if (latest == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("dimension", dimension);
            empty.put("days", safeDays);
            empty.put("current_period", null);
            empty.put("previous_period", null);
            empty.put("thresholds", thresholds);
            empty.put("items", List.of());
            return empty;
        }
        LocalDate anchor = LocalDate.parse(latest);
        String currentFrom = anchor.minusDays(safeDays - 1L).toString();
        String currentTo = anchor.toString();
        LocalDate previousEnd = anchor.minusDays(safeDays);
        String previousFrom = previousEnd.minusDays(safeDays - 1L).toString();
        String previousTo = previousEnd.toString();

        List<Map<String, Object>> items = new ArrayList<>();
        switch (dimension) {
            case "spike" -> spike(safeDays, owner, items);
            case "duplicate" -> duplicate(currentFrom, currentTo, owner, items);
            case "price_hike" -> priceHike(currentFrom, currentTo, owner, items);
            default -> outlier(currentFrom, currentTo, owner, items);
        }

        int safeLimit = Math.min(Math.max(1, limit), 30);
        Map<String, Object> currentPeriod = new LinkedHashMap<>();
        currentPeriod.put("from", currentFrom);
        currentPeriod.put("to", currentTo);
        Map<String, Object> previousPeriod = new LinkedHashMap<>();
        previousPeriod.put("from", previousFrom);
        previousPeriod.put("to", previousTo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("days", safeDays);
        result.put("current_period", currentPeriod);
        result.put("previous_period", previousPeriod);
        result.put("thresholds", thresholds);
        result.put("items", items.subList(0, Math.min(safeLimit, items.size())));
        return result;
    }

    @SuppressWarnings("unchecked")
    private void spike(int days, String owner, List<Map<String, Object>> items) {
        for (Object rowObj : (List<?>) bills.compare(days, owner).get("by_category")) {
            Map<String, Object> row = (Map<String, Object>) rowObj;
            java.math.BigDecimal current = BillRepository.money(row.get("current"));
            java.math.BigDecimal previous = BillRepository.money(row.get("previous"));
            if (current.compareTo(SPIKE_MIN) >= 0
                && current.compareTo(previous.multiply(SPIKE_RATIO)) >= 0) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("current_amount", current.stripTrailingZeros());
                evidence.put("previous_amount", previous.stripTrailingZeros());
                evidence.put("change_percent", row.get("change_percent"));
                items.add(item(row.get("name"),
                    "本期 ¥" + gFormat(current) + ",上期 ¥" + gFormat(previous)
                        + ",达到 " + gFormat(SPIKE_RATIO) + " 倍", evidence));
            }
        }
        items.sort((a, b) -> evidenceMoney(b, "current_amount")
            .compareTo(evidenceMoney(a, "current_amount")));
    }

    private void duplicate(String currentFrom, String currentTo, String owner,
                           List<Map<String, Object>> items) {
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        List<Object> args = new ArrayList<>();
        args.add(currentFrom);
        args.add(currentTo);
        args.addAll(ownerAnd.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT tx_id, merchant, amount, paid_at FROM transactions "
                + "WHERE substr(paid_at, 1, 10) BETWEEN ? AND ?" + ownerAnd.sql()
                + " ORDER BY paid_at", args.toArray());
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("merchant") + "|"
                + BillRepository.money(row.get("amount")).setScale(2,
                    java.math.RoundingMode.HALF_EVEN).stripTrailingZeros();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<LocalDateTime> dates = group.stream()
                .map(row -> parsePaidAt(String.valueOf(row.get("paid_at")))).sorted().toList();
            double minGap = Double.MAX_VALUE;
            for (int i = 1; i < dates.size(); i++) {
                minGap = Math.min(minGap,
                    Duration.between(dates.get(i - 1), dates.get(i)).toSeconds() / 86400.0);
            }
            if (minGap > DUPLICATE_WINDOW_DAYS) {
                continue;
            }
            String gapText = minGap >= 1
                ? String.format("%.1f 天", minGap)
                : String.format("%.0f 分钟", minGap * 1440);
            Map<String, Object> first = group.get(0);
            java.math.BigDecimal amount = BillRepository.money(first.get("amount"));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("tx_ids", group.stream().map(r -> r.get("tx_id")).toList());
            evidence.put("dates", group.stream().map(r -> r.get("paid_at")).toList());
            evidence.put("min_gap_days", BigDecimal.valueOf(minGap)
                .setScale(4, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros());
            items.add(item(first.get("merchant") + " ¥" + gFormat(amount),
                group.size() + " 笔最近间隔 " + gapText, evidence));
        }
        items.sort((a, b) -> {
            int bySize = Integer.compare(
                ((List<?>) ((Map<String, Object>) b.get("evidence")).get("tx_ids")).size(),
                ((List<?>) ((Map<String, Object>) a.get("evidence")).get("tx_ids")).size());
            return bySize != 0 ? bySize
                : String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")));
        });
    }

    private void priceHike(String currentFrom, String currentTo, String owner,
                           List<Map<String, Object>> items) {
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        // 演示数据金额双峰,取窗口内该商户最近一笔实扣与预期比较
        List<Map<String, Object>> subscriptions = jdbc.queryForList(
            "SELECT * FROM subscriptions WHERE active = TRUE" + ownerAnd.sql() + " ORDER BY id",
            ownerAnd.params().toArray());
        for (Map<String, Object> sub : subscriptions) {
            List<Object> args = new ArrayList<>();
            args.add(sub.get("merchant"));
            args.add(currentFrom);
            args.add(currentTo);
            args.addAll(ownerAnd.params());
            List<Map<String, Object>> latestTx = jdbc.queryForList(
                "SELECT amount, paid_at FROM transactions WHERE merchant = ? "
                    + "AND substr(paid_at, 1, 10) BETWEEN ? AND ?" + ownerAnd.sql()
                    + " ORDER BY paid_at DESC LIMIT 1", args.toArray());
            if (latestTx.isEmpty()) {
                continue;
            }
            java.math.BigDecimal expected = BillRepository.money(sub.get("expected_amount"));
            java.math.BigDecimal actual = BillRepository.money(latestTx.get(0).get("amount"));
            java.math.BigDecimal tolerance = HIKE_MIN_ABS.max(expected.multiply(HIKE_RATIO));
            if (actual.subtract(expected).abs().compareTo(tolerance) < 0) {
                continue;
            }
            String direction = actual.compareTo(expected) > 0 ? "上涨" : "回落";
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("expected_amount", expected.stripTrailingZeros());
            evidence.put("actual_amount", actual.stripTrailingZeros());
            evidence.put("latest_date", latestTx.get(0).get("paid_at"));
            evidence.put("merchant", sub.get("merchant"));
            items.add(item(sub.get("name"),
                "预期 ¥" + gFormat(expected) + " 实扣 ¥" + gFormat(actual) + "," + direction,
                evidence));
        }
        items.sort((a, b) -> evidenceMoney(b, "actual_amount")
            .subtract(evidenceMoney(b, "expected_amount")).abs()
            .compareTo(evidenceMoney(a, "actual_amount")
                .subtract(evidenceMoney(a, "expected_amount")).abs()));
    }

    private void outlier(String currentFrom, String currentTo, String owner,
                         List<Map<String, Object>> items) {
        OwnerScope.Clause ownerAndTx = OwnerScope.and(owner, "t.owner");
        List<Object> args = new ArrayList<>();
        args.add(currentFrom);
        args.add(currentTo);
        args.addAll(ownerAndTx.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.tx_id, t.merchant, t.amount, t.paid_at, "
                + "COALESCE(c.name, '未分类') AS category FROM transactions t "
                + "LEFT JOIN categories c ON c.id = t.category_id "
                + "WHERE substr(t.paid_at, 1, 10) BETWEEN ? AND ?" + ownerAndTx.sql(),
            args.toArray());
        Map<String, List<java.math.BigDecimal>> amountsByCategory = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            amountsByCategory.computeIfAbsent(String.valueOf(row.get("category")),
                k -> new ArrayList<>()).add(BillRepository.money(row.get("amount")));
        }
        for (Map<String, Object> row : rows) {
            java.math.BigDecimal amount = BillRepository.money(row.get("amount"));
            if (amount.compareTo(OUTLIER_MIN) < 0) {
                continue;
            }
            List<java.math.BigDecimal> values = amountsByCategory.get(String.valueOf(row.get("category")));
            java.math.BigDecimal sum = values.stream().reduce(java.math.BigDecimal.ZERO,
                java.math.BigDecimal::add);
            java.math.BigDecimal categoryMean = values.isEmpty() ? java.math.BigDecimal.ZERO
                : sum.divide(java.math.BigDecimal.valueOf(values.size()), 6,
                    java.math.RoundingMode.HALF_EVEN);
            if (amount.compareTo(categoryMean.multiply(OUTLIER_RATIO)) < 0) {
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("tx_id", row.get("tx_id"));
            evidence.put("category", row.get("category"));
            evidence.put("amount", amount.stripTrailingZeros());
            evidence.put("category_mean", categoryMean.setScale(2,
                java.math.RoundingMode.HALF_EVEN).stripTrailingZeros());
            items.add(item(row.get("merchant"),
                "¥" + gFormat(amount) + " 为类别均值 "
                    + amount.divide(categoryMean, 1, java.math.RoundingMode.HALF_EVEN)
                        .stripTrailingZeros().toPlainString() + " 倍", evidence));
        }
        items.sort((a, b) -> evidenceMoney(b, "amount")
            .compareTo(evidenceMoney(a, "amount")));
    }

    private static java.math.BigDecimal evidenceMoney(Map<String, Object> item, String key) {
        return BillRepository.money(((Map<String, Object>) item.get("evidence")).get(key));
    }

    private static Map<String, Object> item(Object name, String detail,
                                            Map<String, Object> evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("detail", detail);
        item.put("evidence", evidence);
        return item;
    }

    private static LocalDateTime parsePaidAt(String value) {
        String text = value.strip().replace('T', ' ');
        if (text.length() == 10) {
            return LocalDate.parse(text).atStartOfDay();
        }
        return LocalDateTime.parse(text, new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm")
            .optionalStart().appendPattern(":ss")
            .optionalStart().appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
            .optionalEnd().optionalEnd()
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter());
    }

    /** %g:整数无小数点,小数去尾零。 */
    static String gFormat(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static String gFormat(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
