package io.github.lxyang01.billguard.storage;

import io.github.lxyang01.billguard.bills.BillFilters;
import io.github.lxyang01.billguard.bills.BillPii;
import io.github.lxyang01.billguard.bills.OwnerScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 账单域 PG 仓储(transactions/categories/subscriptions/tx_audits/imports/reports)。
 * 查询组(query/overview/compare)语义与 SQL 逐条对齐 bills.py。
 */
public final class BillRepository {

    private final JdbcTemplate jdbc;

    public BillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- WHERE 构造 ----

    record Where(String sql, List<Object> params) {}

    private static Where where(BillFilters filters, String alias, String owner) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (notBlank(filters.merchant())) {
            clauses.add(alias + ".merchant = ?");
            params.add(filters.merchant());
        }
        if (notBlank(filters.method())) {
            clauses.add(alias + ".method = ?");
            params.add(filters.method());
        }
        if (notBlank(filters.status())) {
            clauses.add(alias + ".status = ?");
            params.add(filters.status());
        }
        if (filters.minAmount() != null) {
            clauses.add(alias + ".amount >= ?");
            params.add(filters.minAmount());
        }
        if (filters.maxAmount() != null) {
            clauses.add(alias + ".amount <= ?");
            params.add(filters.maxAmount());
        }
        // 纯日期自动补全当天边界,避免整点时间被 date_to 排除
        if (notBlank(filters.dateFrom())) {
            clauses.add(alias + ".paid_at >= ?");
            params.add(filters.dateFrom().length() == 10
                ? filters.dateFrom() + " 00:00:00" : filters.dateFrom());
        }
        if (notBlank(filters.dateTo())) {
            clauses.add(alias + ".paid_at <= ?");
            params.add(filters.dateTo().length() == 10
                ? filters.dateTo() + " 23:59:59.999999" : filters.dateTo());
        }
        if (notBlank(filters.category())) {
            clauses.add(alias + ".category_id IN (SELECT id FROM categories WHERE name = ?)");
            params.add(filters.category());
        }
        if (notBlank(filters.query())) {
            clauses.add("(" + alias + ".tx_id LIKE ? OR " + alias + ".merchant LIKE ? OR "
                + alias + ".note LIKE ? OR EXISTS (SELECT 1 FROM categories cx "
                + "WHERE cx.id = " + alias + ".category_id AND cx.name LIKE ?))");
            String value = "%" + filters.query() + "%";
            params.add(value);
            params.add(value);
            params.add(value);
            params.add(value);
        }
        OwnerScope.Clause ownerClause = OwnerScope.clause(owner, alias + ".owner");
        if (!ownerClause.sql().isEmpty()) {
            clauses.add(ownerClause.sql());
            params.addAll(ownerClause.params());
        }
        return clauses.isEmpty()
            ? new Where("", params) : new Where(" WHERE " + String.join(" AND ", clauses), params);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }

    // ---- query ----

    public Map<String, Object> query(BillFilters filters, int page, int pageSize, String owner) {
        BillFilters safe = filters == null ? BillFilters.EMPTY : filters;
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        Where where = where(safe, "t", owner);
        Object[] countArgs = where.params().toArray();
        Integer total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions t" + where.sql(), Integer.class, countArgs);
        List<Object> args = new ArrayList<>(where.params());
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT t.tx_id, t.paid_at, t.merchant, COALESCE(c.name, '未分类') AS category, "
                + "t.amount, t.method, t.status, t.note, t.created_at FROM transactions t "
                + "LEFT JOIN categories c ON c.id = t.category_id" + where.sql()
                + " ORDER BY t.paid_at DESC, t.tx_id LIMIT ? OFFSET ?", args.toArray());
        // note 脱敏:查询出口统一走 mask
        List<Map<String, Object>> masked = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> copy = new LinkedHashMap<>(item);
            copy.put("note", BillPii.mask(String.valueOf(item.getOrDefault("note", ""))).text());
            copy.put("amount", asDouble(item.get("amount")));
            masked.add(copy);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", masked);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("page_size", safeSize);
        if ((total == null ? 0 : total) == 0
            && (notBlank(safe.query()) || notBlank(safe.merchant()))) {
            result.put("retry_hint",
                "当前筛选未命中数据。请放宽筛选条件或缩短关键词后重试一次;重试仍为空时只能报告证据不足。");
        }
        return result;
    }

    // ---- overview ----

    public Map<String, Object> overview(BillFilters filters, String owner) {
        BillFilters safe = filters == null ? BillFilters.EMPTY : filters;
        Where where = where(safe, "t", owner);
        Object[] args = where.params().toArray();

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT COALESCE(SUM(amount), 0) AS total_amount, COUNT(*) AS count, "
                + "COUNT(DISTINCT substr(paid_at, 1, 10)) AS active_days, "
                + "MIN(substr(paid_at, 1, 10)) AS data_from, "
                + "MAX(substr(paid_at, 1, 10)) AS data_to FROM transactions t" + where.sql(),
            args);

        Integer pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions t" + where.sql()
                + (where.sql().isEmpty() ? " WHERE" : " AND")
                + " t.status IN ('待核查','核查中')",
            Integer.class, args);

        List<Map<String, Object>> byCategory = jdbc.queryForList(
            "SELECT COALESCE(c.name, '未分类') AS name, ROUND(SUM(t.amount), 2) AS amount, "
                + "COUNT(*) AS count FROM transactions t "
                + "LEFT JOIN categories c ON c.id = t.category_id" + where.sql()
                + " GROUP BY c.name ORDER BY amount DESC", args);

        List<Map<String, Object>> topMerchants = jdbc.queryForList(
            "SELECT t.merchant AS name, ROUND(SUM(t.amount), 2) AS amount, COUNT(*) AS count "
                + "FROM transactions t" + where.sql()
                + " GROUP BY t.merchant ORDER BY amount DESC LIMIT 8", args);

        List<Map<String, Object>> maxTxRows = jdbc.queryForList(
            "SELECT t.tx_id, t.paid_at, t.merchant, COALESCE(c.name, '未分类') AS category, "
                + "t.amount FROM transactions t LEFT JOIN categories c ON c.id = t.category_id"
                + where.sql() + " ORDER BY t.amount DESC, t.paid_at DESC LIMIT 1", args);

        List<Map<String, Object>> trendDesc = jdbc.queryForList(
            "SELECT substr(t.paid_at, 1, 10) AS date, ROUND(SUM(t.amount), 2) AS amount "
                + "FROM transactions t" + where.sql()
                + " GROUP BY date ORDER BY date DESC LIMIT 90", args);
        List<Map<String, Object>> trend = new ArrayList<>(trendDesc);
        java.util.Collections.reverse(trend);

        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "owner");
        List<Object> ownerParams = new ArrayList<>(ownerWhere.params());
        List<String> categories = jdbc.queryForList(
            "SELECT name FROM categories" + ownerWhere.sql() + " ORDER BY name",
            String.class, ownerParams.toArray());
        List<String> methods = jdbc.queryForList(
            "SELECT DISTINCT method FROM transactions" + ownerWhere.sql() + " ORDER BY method",
            String.class, ownerParams.toArray());

        long count = ((Number) row.get("count")).longValue();
        double totalAmount = asDouble(row.get("total_amount"));
        long activeDays = ((Number) row.get("active_days")).longValue();
        String dataNote = null;
        if (count == 0) {
            Integer unfiltered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions" + ownerWhere.sql(), Integer.class,
                ownerParams.toArray());
            dataNote = (unfiltered != null && unfiltered == 0)
                ? "账单库为空(无任何交易记录),请先导入 CSV"
                : "当前筛选条件下无交易记录";
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("categories", categories);
        options.put("methods", methods);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_amount", round2(totalAmount));
        result.put("count", count);
        result.put("pending", pending == null ? 0 : pending);
        result.put("avg_daily", activeDays > 0 ? round2(totalAmount / activeDays) : 0.0);
        // 空结果集时为 null:给模型日期锚点,避免编造年份/区间
        result.put("data_from", row.get("data_from"));
        result.put("data_to", row.get("data_to"));
        result.put("data_note", dataNote);
        result.put("by_category", byCategory);
        result.put("top_merchants", topMerchants);
        result.put("max_tx", maxTxRows.isEmpty() ? null : maxTxRows.get(0));
        result.put("trend", trend);
        result.put("options", options);
        return result;
    }

    // ---- compare ----

    public Map<String, Object> compare(int days, String owner) {
        int safeDays = Math.min(Math.max(1, days), 90);
        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "owner");
        // 锚点=该 owner 数据的最新日期,避免窗口被别人的数据带偏
        String latest = jdbc.queryForObject(
            "SELECT MAX(substr(paid_at, 1, 10)) FROM transactions" + ownerWhere.sql(),
            String.class, ownerWhere.params().toArray());
        java.time.LocalDate anchor = latest != null
            ? java.time.LocalDate.parse(latest) : java.time.LocalDate.now();
        java.time.LocalDate currentStart = anchor.minusDays(safeDays - 1L);
        java.time.LocalDate previousEnd = currentStart.minusDays(1);
        java.time.LocalDate previousStart = previousEnd.minusDays(safeDays - 1L);
        Map<String, Object> current = overview(new BillFilters(currentStart.toString(),
            anchor.toString(), "", "", "", "", null, null, ""), owner);
        Map<String, Object> previous = overview(new BillFilters(previousStart.toString(),
            previousEnd.toString(), "", "", "", "", null, null, ""), owner);

        double currentTotal = asDouble(current.get("total_amount"));
        double previousTotal = asDouble(previous.get("total_amount"));
        Double change = previousTotal == 0 ? null
            : round((currentTotal - previousTotal) / previousTotal * 100, 1);

        Map<String, double[]> merged = new LinkedHashMap<>();
        for (Object item : (List<?>) current.get("by_category")) {
            Map<?, ?> row = (Map<?, ?>) item;
            merged.computeIfAbsent(String.valueOf(row.get("name")),
                k -> new double[]{0.0, 0.0})[0] = asDouble(row.get("amount"));
        }
        for (Object item : (List<?>) previous.get("by_category")) {
            Map<?, ?> row = (Map<?, ?>) item;
            merged.computeIfAbsent(String.valueOf(row.get("name")),
                k -> new double[]{0.0, 0.0})[1] = asDouble(row.get("amount"));
        }
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : merged.entrySet()) {
            double currentAmount = entry.getValue()[0];
            double previousAmount = entry.getValue()[1];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("current", round2(currentAmount));
            row.put("previous", round2(previousAmount));
            row.put("change_percent", previousAmount == 0 ? null
                : round((currentAmount - previousAmount) / previousAmount * 100, 1));
            byCategory.add(row);
        }
        byCategory.sort((a, b) -> {
            int byAmount = Double.compare(asDouble(b.get("current")), asDouble(a.get("current")));
            return byAmount != 0 ? byAmount
                : String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")));
        });

        Map<String, Object> currentPeriod = new LinkedHashMap<>();
        currentPeriod.put("from", currentStart.toString());
        currentPeriod.put("to", anchor.toString());
        currentPeriod.put("total", current.get("total_amount"));
        Map<String, Object> previousPeriod = new LinkedHashMap<>();
        previousPeriod.put("from", previousStart.toString());
        previousPeriod.put("to", previousEnd.toString());
        previousPeriod.put("total", previous.get("total_amount"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", safeDays);
        result.put("current_period", currentPeriod);
        result.put("previous_period", previousPeriod);
        result.put("change_percent", change);
        result.put("by_category", byCategory);
        return result;
    }

    // ---- 导入与类别 ----

    public static final java.util.List<String[]> DEFAULT_CATEGORIES = List.of(
        new String[]{"餐饮", "饿了么,美团,肯德基,麦当劳,咖啡,午餐,晚餐,奶茶"},
        new String[]{"交通", "滴滴,地铁,公交,高铁,加油,停车"},
        new String[]{"购物", "淘宝,京东,拼多多,天猫,超市"},
        new String[]{"订阅", "会员,订阅,月费,年费,自动续费"},
        new String[]{"娱乐", "电影,游戏,Steam,演出"},
        new String[]{"居住", "房租,水电,物业,燃气"});

    public Map<String, Object> importBills(String filename, String csvText, String owner) {
        if (csvText == null || csvText.strip().isEmpty()) {
            throw new IllegalArgumentException("CSV 内容为空");
        }
        var parsed = io.github.lxyang01.billguard.bills.CsvImport.parseBills(csvText);
        int imported = 0;
        int duplicates = 0;
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        List<CategoryRule> rules = categoryRules(owner);
        for (var row : parsed.rows()) {
            // 去重按 (owner, tx_id) 复合唯一:同号账单可在不同 owner 名下各自入库
            int inserted = jdbc.update(
                "INSERT INTO transactions(tx_id, paid_at, merchant, note, amount, method, "
                    + "created_at, owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (COALESCE(owner, ''), tx_id) DO NOTHING",
                row.txId(), row.paidAt(), row.merchant(), row.note(),
                BigDecimal.valueOf(row.amount()), row.method(), now, owner);
            if (inserted == 0) {
                duplicates++;
                continue;
            }
            imported++;
            Long categoryId = resolveCategory(rules, row.categoryName(), row.merchant(),
                row.note(), now, owner);
            jdbc.update("UPDATE transactions SET category_id = ? WHERE tx_id = ? AND "
                    + "COALESCE(owner, '') = COALESCE(?, '')",
                categoryId, row.txId(), owner);
        }
        return importSummary(filename, parsed.total(), imported, duplicates, parsed.errors(),
                owner);
    }

    public Map<String, Object> importSubscriptions(String filename, String csvText, String owner) {
        if (csvText == null || csvText.strip().isEmpty()) {
            throw new IllegalArgumentException("CSV 内容为空");
        }
        var parsed = io.github.lxyang01.billguard.bills.CsvImport.parseSubscriptions(csvText);
        int imported = 0;
        int duplicates = 0;
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        for (var row : parsed.rows()) {
            List<Object> args = new ArrayList<>();
            args.add(row.name());
            args.add(row.merchant());
            args.addAll(ownerAnd.params());
            List<?> exists = jdbc.queryForList(
                "SELECT id FROM subscriptions WHERE name = ? AND merchant = ?" + ownerAnd.sql(),
                args.toArray());
            if (!exists.isEmpty()) {
                duplicates++;
                continue;
            }
            jdbc.update("INSERT INTO subscriptions(name, merchant, cycle, expected_amount, "
                    + "created_at, owner) VALUES (?, ?, ?, ?, ?, ?)",
                row.name(), row.merchant(), row.cycle(), BigDecimal.valueOf(row.expectedAmount()),
                now, owner);
            imported++;
        }
        return importSummary(filename, parsed.total(), imported, duplicates, parsed.errors(),
                owner);
    }

    private Map<String, Object> importSummary(String filename, int total, int imported,
                                              int duplicates, List<String> errors, String owner) {
        String status = switch (errors.size()) {
            case 0 -> "completed";
            default -> imported > 0 ? "partial" : "failed";
        };
        List<String> capped = errors.size() > 20 ? errors.subList(0, 20) : errors;
        jdbc.update("INSERT INTO imports(filename, total_rows, imported_rows, duplicate_rows, "
                + "failed_rows, failed_reasons, status, imported_at, owner) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
            filename, total, imported, duplicates, errors.size(),
            PgJson.value(capped), status, io.github.lxyang01.agent.types.Timestamps.nowIso(),
            owner);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("total_rows", total);
        result.put("imported_rows", imported);
        result.put("duplicate_rows", duplicates);
        result.put("failed_rows", errors.size());
        result.put("status", status);
        result.put("errors", capped);
        return result;
    }

    record CategoryRule(long id, String name, List<String> keywords) {}

    /** 自动分类只看同一 owner 的启用规则。 */
    List<CategoryRule> categoryRules(String owner) {
        OwnerScope.Clause clause = OwnerScope.clause(owner, "owner");
        List<Object> args = new ArrayList<>(clause.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, name, keywords FROM categories WHERE enabled = TRUE AND "
                + clause.sql() + " ORDER BY id", args.toArray());
        List<CategoryRule> rules = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> keywords = new ArrayList<>();
            try {
                keywords = io.github.lxyang01.agent.util.Json.MAPPER.readValue(
                    String.valueOf(row.get("keywords")),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception ignored) {
                // 坏 keywords 视为空规则
            }
            rules.add(new CategoryRule(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("name")), keywords));
        }
        return rules;
    }

    /** 类别名不存在时即时创建(幂等)。 */
    long ensureCategory(String name, String now, String owner) {
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
                + "VALUES (?, '[]', TRUE, ?, ?) ON CONFLICT DO NOTHING",
            name, now, owner);
        OwnerScope.Clause clause = OwnerScope.clause(owner, "owner");
        List<Object> args = new ArrayList<>();
        args.add(name);
        args.addAll(clause.params());
        Long id = jdbc.queryForObject("SELECT id FROM categories WHERE name = ? AND "
            + clause.sql(), Long.class, args.toArray());
        return id == null ? 0 : id;
    }

    /** 类别名 → 关键词规则 → 兜底"其他"。 */
    long resolveCategory(List<CategoryRule> rules, String categoryName, String merchant,
                         String note, String now, String owner) {
        if (categoryName != null && !categoryName.isEmpty()) {
            OwnerScope.Clause clause = OwnerScope.clause(owner, "owner");
            List<Object> args = new ArrayList<>();
            args.add(categoryName);
            args.addAll(clause.params());
            List<Long> found = jdbc.queryForList("SELECT id FROM categories WHERE name = ? AND "
                    + clause.sql(), Long.class, args.toArray());
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        String haystack = (merchant + " " + note).toLowerCase(java.util.Locale.ROOT);
        for (CategoryRule rule : rules) {
            for (String keyword : rule.keywords()) {
                if (haystack.contains(String.valueOf(keyword).toLowerCase(java.util.Locale.ROOT))) {
                    return rule.id();
                }
            }
        }
        return ensureCategory("其他", now, owner);
    }

    /** 首次访问时为无任何类别的 owner 播种默认类别副本(带 owner 戳)。 */
    public void ensureUserCategories(String owner) {
        OwnerScope.Clause clause = OwnerScope.clause(owner, "owner");
        List<Object> args = new ArrayList<>(clause.params());
        List<?> exists = jdbc.queryForList(
            "SELECT 1 FROM categories WHERE " + clause.sql() + " LIMIT 1", args.toArray());
        if (!exists.isEmpty()) {
            return;
        }
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        for (String[] entry : DEFAULT_CATEGORIES) {
            List<String> keywords = java.util.Arrays.stream(entry[1].split(","))
                .map(String::strip).toList();
            jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
                    + "VALUES (?, ?::jsonb, TRUE, ?, ?)",
                entry[0], PgJson.value(keywords), now, owner);
        }
    }

    // ---- 数值规整 ----

    static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return 0.0;
    }

    /** Python round(x, 2)(HALF_EVEN)。 */
    static double round2(double value) {
        return round(value, 2);
    }

    static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN).doubleValue();
    }
}
