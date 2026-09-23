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
            copy.put("amount", money(item.get("amount")));
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
        BigDecimal totalAmount = money(row.get("total_amount"));
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
        result.put("total_amount", totalAmount.setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());
        result.put("count", count);
        result.put("pending", pending == null ? 0 : pending);
        result.put("avg_daily", activeDays > 0
            ? totalAmount.divide(BigDecimal.valueOf(activeDays), 2, RoundingMode.HALF_EVEN)
                .stripTrailingZeros()
            : BigDecimal.ZERO);
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

        BigDecimal currentTotal = money(current.get("total_amount"));
        BigDecimal previousTotal = money(previous.get("total_amount"));
        BigDecimal change = previousTotal.signum() == 0 ? null
            : currentTotal.subtract(previousTotal)
                .divide(previousTotal, 4, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_EVEN);

        Map<String, BigDecimal[]> merged = new LinkedHashMap<>();
        for (Object item : (List<?>) current.get("by_category")) {
            Map<?, ?> row = (Map<?, ?>) item;
            merged.computeIfAbsent(String.valueOf(row.get("name")),
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[0] =
                money(row.get("amount"));
        }
        for (Object item : (List<?>) previous.get("by_category")) {
            Map<?, ?> row = (Map<?, ?>) item;
            merged.computeIfAbsent(String.valueOf(row.get("name")),
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[1] =
                money(row.get("amount"));
        }
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> entry : merged.entrySet()) {
            BigDecimal currentAmount = entry.getValue()[0];
            BigDecimal previousAmount = entry.getValue()[1];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("current", currentAmount.setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());
            row.put("previous", previousAmount.setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());
            row.put("change_percent", previousAmount.signum() == 0 ? null
                : currentAmount.subtract(previousAmount)
                    .divide(previousAmount, 4, RoundingMode.HALF_EVEN)
                    .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_EVEN));
            byCategory.add(row);
        }
        byCategory.sort((a, b) -> {
            int byAmount = money(b.get("current")).compareTo(money(a.get("current")));
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
                row.amount(), row.method(), now, owner);
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
                row.name(), row.merchant(), row.cycle(), row.expectedAmount(),
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


    // ---- 类别规则 / 工作流 / 订阅 / 报告 / 导出 / 清空 ----

    public static final java.util.List<String> WORKFLOW_STATUSES =
        List.of("正常", "待核查", "核查中", "已确认", "已忽略");

    public List<Map<String, Object>> categories(String owner) {
        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "c.owner");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.id, c.name, c.keywords, c.enabled, c.created_at, COUNT(t.tx_id) AS count "
                + "FROM categories c LEFT JOIN transactions t ON t.category_id = c.id"
                + ownerWhere.sql() + " GROUP BY c.id ORDER BY count DESC, c.name",
            ownerWhere.params().toArray());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("keywords", parseKeywords(String.valueOf(row.get("keywords"))));
            item.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
            item.put("count", ((Number) row.get("count")).longValue());
            result.add(item);
        }
        return result;
    }

    private static List<String> parseKeywords(String json) {
        try {
            return io.github.lxyang01.agent.util.Json.MAPPER.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> saveCategory(String name, List<String> keywords, boolean enabled,
                                            Long categoryId, String operator, String owner) {
        String cleanName = name == null ? "" : name.strip();
        List<String> cleaned = keywords == null ? List.of()
            : keywords.stream().map(String::strip).filter(k -> !k.isEmpty()).distinct().toList();
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException("类别名称不能为空");
        }
        if (cleanName.length() > 40) {
            throw new IllegalArgumentException("类别名不能超过 40 字符");
        }
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        Map<String, Object> old = new LinkedHashMap<>();
        String action = "create";
        Long id = categoryId;
        if (id != null) {
            List<Object> args = new ArrayList<>();
            args.add(id);
            args.addAll(ownerAnd.params());
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM categories WHERE id = ?" + ownerAnd.sql(), args.toArray());
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("类别不存在：" + id);
            }
            Map<String, Object> row = rows.get(0);
            old.put("name", row.get("name"));
            old.put("keywords", parseKeywords(String.valueOf(row.get("keywords"))));
            old.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
            try {
                List<Object> args2 = new ArrayList<>();
                args2.add(cleanName);
                args2.add(PgJson.value(cleaned));
                args2.add(enabled);
                args2.add(id);
                args2.addAll(ownerAnd.params());
                jdbc.update("UPDATE categories SET name = ?, keywords = ?, enabled = ? "
                    + "WHERE id = ?" + ownerAnd.sql(), args2.toArray());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new IllegalArgumentException("类别名称已存在：" + cleanName);
            }
            action = "update";
        } else {
            try {
                org.springframework.jdbc.support.KeyHolder holder =
                    new org.springframework.jdbc.support.GeneratedKeyHolder();
                String keywordsJson = io.github.lxyang01.agent.util.Json.write(cleaned);
                jdbc.update(con -> {
                    var ps = con.prepareStatement("INSERT INTO categories(name, keywords, "
                        + "enabled, created_at, owner) VALUES (?, ?::jsonb, ?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, cleanName);
                    ps.setString(2, keywordsJson);
                    ps.setBoolean(3, enabled);
                    ps.setString(4, now);
                    ps.setString(5, owner);
                    return ps;
                }, holder);
                Object key = holder.getKeys().get("id");
                id = key == null ? null : ((Number) key).longValue();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new IllegalArgumentException("类别名称已存在：" + cleanName);
            }
        }
        Map<String, Object> newRule = new LinkedHashMap<>();
        newRule.put("name", cleanName);
        newRule.put("keywords", cleaned);
        newRule.put("enabled", enabled);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("rule_action", action);
        audit.put("id", id);
        audit.put("old", old);
        audit.put("new", newRule);
        jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, owner) "
                + "VALUES ('', 'category', ?, ?::jsonb, ?, ?)",
            operator, PgJson.value(audit), now, owner);
        Map<String, Object> result = new LinkedHashMap<>(newRule);
        result.put("id", id);
        result.put("created_at", now);
        return result;
    }

    public Map<String, Object> deleteCategory(long categoryId, String operator, String owner) {
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        List<Object> args = new ArrayList<>();
        args.add(categoryId);
        args.addAll(ownerAnd.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM categories WHERE id = ?" + ownerAnd.sql(), args.toArray());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("类别不存在：" + categoryId);
        }
        Map<String, Object> row = rows.get(0);
        List<Object> args2 = new ArrayList<>();
        args2.add(categoryId);
        args2.addAll(ownerAnd.params());
        List<String> affected = jdbc.queryForList(
            "SELECT tx_id FROM transactions WHERE category_id = ?" + ownerAnd.sql(),
            String.class, args2.toArray());
        // 引用该类别的交易置空,由 rematch 或关键词规则重新归类
        jdbc.update("UPDATE transactions SET category_id = NULL WHERE category_id = ?"
            + ownerAnd.sql(), args2.toArray());
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("name", row.get("name"));
        old.put("keywords", parseKeywords(String.valueOf(row.get("keywords"))));
        old.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("rule_action", "delete");
        audit.put("id", categoryId);
        audit.put("old", old);
        audit.put("unassigned", affected);
        jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, owner) "
                + "VALUES ('', 'category', ?, ?::jsonb, ?, ?)",
            operator, PgJson.value(audit), now, owner);
        jdbc.update("DELETE FROM categories WHERE id = ?" + ownerAnd.sql(), args2.toArray());
        Map<String, Object> deleted = new LinkedHashMap<>();
        deleted.put("id", categoryId);
        deleted.putAll(old);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("unassigned", affected.size());
        return result;
    }

    public Map<String, Object> rematchCategories(String operator, String owner) {
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        List<CategoryRule> rules = categoryRules(owner);
        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "owner");
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        long otherId = 0;
        int changed = 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT tx_id, merchant, note, category_id FROM transactions" + ownerWhere.sql(),
            ownerWhere.params().toArray());
        for (Map<String, Object> row : rows) {
            String haystack = (row.get("merchant") + " " + row.get("note"))
                .toLowerCase(java.util.Locale.ROOT);
            Long newId = null;
            for (CategoryRule rule : rules) {
                boolean hit = rule.keywords().stream().anyMatch(keyword ->
                    haystack.contains(String.valueOf(keyword)
                        .toLowerCase(java.util.Locale.ROOT)));
                if (hit) {
                    newId = rule.id();
                    break;
                }
            }
            if (newId == null) {
                if (otherId == 0) {
                    otherId = ensureCategory("其他", now, owner);
                }
                newId = otherId;
            }
            Long current = row.get("category_id") == null ? null
                : ((Number) row.get("category_id")).longValue();
            if (newId.equals(current)) {
                continue;
            }
            List<Object> args = new ArrayList<>();
            args.add(newId);
            args.add(row.get("tx_id"));
            args.addAll(ownerAnd.params());
            jdbc.update("UPDATE transactions SET category_id = ? WHERE tx_id = ?"
                + ownerAnd.sql(), args.toArray());
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("category_id", newId);
            audit.put("source", "rematch");
            jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, "
                    + "owner) VALUES (?, 'category', ?, ?::jsonb, ?, ?)",
                row.get("tx_id"), operator, PgJson.value(audit), now, owner);
            changed++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactions", rows.size());
        result.put("changed", changed);
        result.put("rule_count", rules.size());
        return result;
    }

    public Map<String, Object> updateWorkflow(List<String> txIds, String operator, String status,
                                              String note, String owner) {
        List<String> ids = txIds.stream().map(s -> s == null ? "" : s.strip())
            .filter(s -> !s.isEmpty()).distinct().toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("至少选择一条交易");
        }
        if (ids.size() > 200) {
            throw new IllegalArgumentException("单次最多处理 200 条交易");
        }
        if (!WORKFLOW_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 必须是" + String.join("、", WORKFLOW_STATUSES));
        }
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", status);
        changes.put("note", note == null ? "" : note.strip());
        List<String> updated = new ArrayList<>();
        for (String txId : ids) {
            List<Object> args = new ArrayList<>();
            args.add(txId);
            args.addAll(OwnerScope.and(owner, "owner").params());
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM transactions WHERE tx_id = ?"
                    + OwnerScope.and(owner, "owner").sql(), args.toArray());
            if (rows.isEmpty()) {
                continue;
            }
            List<Object> args2 = new ArrayList<>();
            args2.add(status);
            args2.add(txId);
            args2.addAll(OwnerScope.and(owner, "owner").params());
            jdbc.update("UPDATE transactions SET status = ? WHERE tx_id = ?"
                + OwnerScope.and(owner, "owner").sql(), args2.toArray());
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("old_status", rows.get(0).get("status"));
            audit.putAll(changes);
            jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, "
                    + "owner) VALUES (?, 'workflow', ?, ?::jsonb, ?, ?)",
                txId, operator, PgJson.value(audit), now, owner);
            updated.add(txId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated_tx_ids", updated);
        result.put("count", updated.size());
        result.put("changes", changes);
        result.put("operator", operator);
        return result;
    }

    public List<Map<String, Object>> transactionAudits(String txId, int limit, String owner) {
        // 审计行自带 owner 戳:同号交易可在多个 owner 名下,双 owner 过滤防越权带出
        OwnerScope.Clause auditAnd = OwnerScope.and(owner, "a.owner");
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "t.owner");
        List<Object> args = new ArrayList<>();
        args.add(txId);
        args.addAll(auditAnd.params());
        args.addAll(ownerAnd.params());
        args.add(Math.min(Math.max(1, limit), 200));
        return jdbc.queryForList(
            "SELECT a.* FROM tx_audits a WHERE a.tx_id = ?" + auditAnd.sql() + " AND EXISTS "
                + "(SELECT 1 FROM transactions t WHERE t.tx_id = a.tx_id" + ownerAnd.sql()
                + ") ORDER BY a.id LIMIT ?", args.toArray());
    }

    public List<Map<String, Object>> recentAudits(int limit, String owner) {
        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "owner");
        List<Object> args = new ArrayList<>(ownerWhere.params());
        args.add(Math.min(Math.max(1, limit), 200));
        return jdbc.queryForList("SELECT * FROM tx_audits" + ownerWhere.sql()
            + " ORDER BY id DESC LIMIT ?", args.toArray());
    }

    public List<Map<String, Object>> imports(int limit, String owner) {
        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "owner");
        List<Object> args = new ArrayList<>(ownerWhere.params());
        args.add(Math.min(Math.max(1, limit), 100));
        return jdbc.queryForList("SELECT * FROM imports" + ownerWhere.sql()
            + " ORDER BY id DESC LIMIT ?", args.toArray());
    }

    public Map<String, Object> updateTransactionCategory(String txId, String category,
                                                         String operator, String owner) {
        String safeTxId = txId == null ? "" : txId.strip();
        String safeCategory = category == null ? "" : category.strip();
        if (safeTxId.isEmpty() || safeCategory.isEmpty()) {
            throw new IllegalArgumentException("tx_id 和 category 不能为空");
        }
        if (safeCategory.length() > 40) {
            throw new IllegalArgumentException("类别名不能超过 40 字符");
        }
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "t.owner");
        List<Object> args = new ArrayList<>();
        args.add(safeTxId);
        args.addAll(ownerAnd.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.tx_id, COALESCE(c.name, '未分类') AS category FROM transactions t "
                + "LEFT JOIN categories c ON c.id = t.category_id WHERE t.tx_id = ?"
                + ownerAnd.sql(), args.toArray());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("交易不存在：" + safeTxId);
        }
        String oldCategory = String.valueOf(rows.get(0).get("category"));
        long categoryId = ensureCategory(safeCategory, now, owner);
        List<Object> args2 = new ArrayList<>();
        args2.add(categoryId);
        args2.add(safeTxId);
        args2.addAll(OwnerScope.and(owner, "owner").params());
        jdbc.update("UPDATE transactions SET category_id = ? WHERE tx_id = ?"
            + OwnerScope.and(owner, "owner").sql(), args2.toArray());
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("old_category", oldCategory);
        audit.put("new_category", safeCategory);
        audit.put("source", "manual");
        jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, owner) "
                + "VALUES (?, 'category', ?, ?::jsonb, ?, ?)",
            safeTxId, operator, PgJson.value(audit), now, owner);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tx_id", safeTxId);
        result.put("old_category", oldCategory);
        result.put("new_category", safeCategory);
        result.put("operator", operator);
        return result;
    }

    public List<Map<String, Object>> subscriptions(String owner) {
        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "s.owner");
        OwnerScope.Clause ownerAndTx = OwnerScope.and(owner, "t.owner");
        List<Object> args = new ArrayList<>(ownerAndTx.params());
        args.addAll(ownerWhere.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.*, (SELECT MAX(t.paid_at) FROM transactions t WHERE t.merchant = s.merchant"
                + ownerAndTx.sql() + ") AS last_paid_at FROM subscriptions s"
                + ownerWhere.sql() + " ORDER BY s.id", args.toArray());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("active", Boolean.TRUE.equals(row.get("active")));
            item.put("expected_amount", money(row.get("expected_amount")));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> setSubscriptionActive(long subscriptionId, boolean active,
                                                     String operator, String owner) {
        String now = io.github.lxyang01.agent.types.Timestamps.nowIso();
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        List<Object> args = new ArrayList<>();
        args.add(subscriptionId);
        args.addAll(ownerAnd.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM subscriptions WHERE id = ?" + ownerAnd.sql(), args.toArray());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("订阅不存在：" + subscriptionId);
        }
        List<Object> args2 = new ArrayList<>();
        args2.add(active);
        args2.add(subscriptionId);
        args2.addAll(ownerAnd.params());
        jdbc.update("UPDATE subscriptions SET active = ? WHERE id = ?" + ownerAnd.sql(),
            args2.toArray());
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("id", subscriptionId);
        audit.put("name", rows.get(0).get("name"));
        audit.put("active", active);
        jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, owner) "
                + "VALUES ('', 'subscription', ?, ?::jsonb, ?, ?)",
            operator, PgJson.value(audit), now, owner);
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("active", active);
        return result;
    }

    public Map<String, Object> saveReport(String sessionId, String title, String content,
                                          String owner) {
        String strippedTitle = title == null ? "" : title.strip();
        final String safeTitle = strippedTitle.length() > 160
            ? strippedTitle.substring(0, 160) : strippedTitle;
        String safeContent = content == null ? "" : content.strip();
        if (safeTitle.isEmpty() || safeContent.isEmpty()) {
            throw new IllegalArgumentException("报告标题和内容不能为空");
        }
        String createdAt = io.github.lxyang01.agent.types.Timestamps.nowIso();
        org.springframework.jdbc.support.KeyHolder holder =
            new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO reports(session_id, title, content, "
                + "created_at, owner) VALUES (?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, safeTitle);
            ps.setString(3, safeContent);
            ps.setString(4, createdAt);
            ps.setString(5, owner);
            return ps;
        }, holder);
        Map<String, Object> result = new LinkedHashMap<>();
        Object reportKey = holder.getKeys().get("id");
        result.put("id", reportKey);
        result.put("session_id", sessionId);
        result.put("title", safeTitle);
        result.put("content", safeContent);
        result.put("created_at", createdAt);
        return result;
    }

    public List<Map<String, Object>> reports(int limit, String owner) {
        OwnerScope.Clause ownerWhere = OwnerScope.where(owner, "owner");
        List<Object> args = new ArrayList<>(ownerWhere.params());
        args.add(Math.min(Math.max(1, limit), 200));
        return jdbc.queryForList("SELECT id, session_id, title, content, created_at FROM reports"
            + ownerWhere.sql() + " ORDER BY id DESC LIMIT ?", args.toArray());
    }

    public Map<String, Object> deleteReport(long reportId, String owner) {
        OwnerScope.Clause ownerAnd = OwnerScope.and(owner, "owner");
        List<Object> args = new ArrayList<>();
        args.add(reportId);
        args.addAll(ownerAnd.params());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, title FROM reports WHERE id = ?" + ownerAnd.sql(), args.toArray());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("报告不存在：" + reportId);
        }
        jdbc.update("DELETE FROM reports WHERE id = ?" + ownerAnd.sql(), args.toArray());
        return Map.of("deleted", rows.get(0));
    }

    public String exportCsv(BillFilters filters, String owner) {
        Where where = where(filters == null ? BillFilters.EMPTY : filters, "t", owner);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.tx_id, t.paid_at, t.merchant, COALESCE(c.name, '未分类') AS category, "
                + "t.amount, t.method, t.note FROM transactions t "
                + "LEFT JOIN categories c ON c.id = t.category_id" + where.sql()
                + " ORDER BY t.paid_at DESC, t.tx_id", where.params().toArray());
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("tx_id,paid_at,merchant,category,amount,method,note\n");
        for (Map<String, Object> row : rows) {
            List<String> cells = List.of(
                csv(String.valueOf(row.get("tx_id"))), csv(String.valueOf(row.get("paid_at"))),
                csv(String.valueOf(row.get("merchant"))), csv(String.valueOf(row.get("category"))),
                csv(String.valueOf(money(row.get("amount")))),
                csv(String.valueOf(row.get("method"))), csv(String.valueOf(row.get("note"))));
            sb.append(String.join(",", cells)).append("\n");
        }
        return sb.toString();
    }

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public Map<String, Object> purgeOwner(String owner, String operator, String note) {
        // admin 的可见范围包含 NULL 存量行,清空语义与其视图一致:连带清除
        String where = "admin".equals(owner)
            ? "owner = ? OR owner IS NULL" : "owner = ?";
        Object[] params = {owner};
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("transactions", (long) jdbc.update(
            "DELETE FROM transactions WHERE " + where, params));
        counts.put("subscriptions", (long) jdbc.update(
            "DELETE FROM subscriptions WHERE " + where, params));
        counts.put("categories", (long) jdbc.update(
            "DELETE FROM categories WHERE " + where, params));
        counts.put("reports", (long) jdbc.update(
            "DELETE FROM reports WHERE " + where, params));
        jdbc.update("DELETE FROM tx_audits WHERE " + where, params);
        jdbc.update("DELETE FROM imports WHERE " + where, params);
        if (operator != null && !operator.isEmpty()) {
            // 清空后留一条审计标记,证明发生过自助清空
            jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, "
                    + "owner) VALUES ('__purge__', 'purge', ?, ?, ?, ?)",
                operator, note == null ? "" : note.substring(0, Math.min(200, note.length())),
                io.github.lxyang01.agent.types.Timestamps.nowIso(), owner);
        }
        return counts;
    }

    // ---- 数值规整 ----


    /** 金额统一 BigDecimal(PG NUMERIC → JDBC BigDecimal 已直配;防御数值型转换)。 */
    static BigDecimal money(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value == null ? "0" : value));
    }
}
