package io.github.lxyang01.billguard.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.bills.BillFilters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 账单查询组:owner 三态隔离、筛选、总览、对比(对齐 bills.py 语义)。 */
class BillRepositoryQueryTest extends PgTestBase {

    BillRepository bills;

    @BeforeEach
    void init() {
        bills = new BillRepository(jdbc);
        seed();
    }

    private void seed() {
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
            + "VALUES ('餐饮', '[\"美团\"]', TRUE, '2026-01-01 00:00:00+00:00', 'alice')");
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
            + "VALUES ('交通', '[]', TRUE, '2026-01-01 00:00:00+00:00', 'alice')");
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at) "
            + "VALUES ('餐饮', '[]', TRUE, '2026-01-01 00:00:00+00:00')");   // NULL 存量
        Long aliceFood = jdbc.queryForObject(
            "SELECT id FROM categories WHERE name='餐饮' AND owner='alice'", Long.class);
        Long aliceTraffic = jdbc.queryForObject(
            "SELECT id FROM categories WHERE name='交通' AND owner='alice'", Long.class);
        Long legacyFood = jdbc.queryForObject(
            "SELECT id FROM categories WHERE name='餐饮' AND owner IS NULL", Long.class);

        insert("t1", "2026-03-01 08:00:00", "美团", aliceFood, "35.50", "alice", "正常",
            "午餐 SO-1234567");
        insert("t2", "2026-03-02 09:30:00", "滴滴", aliceTraffic, "12.00", "alice", "待核查", "");
        insert("t3", "2026-03-02 21:00:00", "美团", aliceFood, "88.80", "alice", "正常", "晚餐");
        insert("t4", "2026-02-28 10:00:00", "京东", null, "199.00", "alice", "正常", "年货");
        // 他人与存量 NULL 行:alice 不可见;admin 可见 NULL
        insert("b1", "2026-03-03 10:00:00", "商户B", null, "50.00", "bob", "正常", "");
        insert("n1", "2026-03-03 11:00:00", "旧商户", legacyFood, "66.00", null, "正常", "");
    }

    private void insert(String txId, String paidAt, String merchant, Long categoryId,
                        String amount, String owner, String status, String note) {
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, category_id, "
                + "amount, method, status, created_at, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
            txId, paidAt, merchant, note, categoryId, new java.math.BigDecimal(amount), "支付宝",
            status, "2026-03-04 00:00:00+00:00", owner);
    }

    @Test
    void query_respects_owner_three_state_scoping() {
        List<Map<String, Object>> aliceItems = (List<Map<String, Object>>) (List<?>) bills.query(
            BillFilters.EMPTY, 1, 50, "alice").get("items");
        assertThat(aliceItems).hasSize(4);           // 只见本人行
        assertThat(aliceItems).allSatisfy(
            item -> assertThat(String.valueOf(item.get("tx_id"))).startsWith("t"));

        // admin 语义 = 本人行 + NULL 存量(不是全体数据;对齐 _owner_clause)
        List<Map<String, Object>> adminItems = (List<Map<String, Object>>) (List<?>) bills.query(
            BillFilters.EMPTY, 1, 50, "admin").get("items");
        assertThat(adminItems).hasSize(1);
        assertThat(adminItems.get(0).get("tx_id")).isEqualTo("n1");

        List<Map<String, Object>> legacyOnly = (List<Map<String, Object>>) (List<?>) bills.query(
            BillFilters.EMPTY, 1, 50, "").get("items");
        assertThat(legacyOnly).hasSize(1);           // 空 owner = 仅 NULL 行
        assertThat(legacyOnly.get(0).get("tx_id")).isEqualTo("n1");
    }

    @Test
    void query_masks_pii_in_note() {
        Map<String, Object> result = bills.query(BillFilters.EMPTY, 1, 50, "alice");
        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) result.get("items");
        Map<String, Object> t1 = items.stream()
            .filter(i -> "t1".equals(i.get("tx_id"))).findFirst().orElseThrow();
        assertThat(t1.get("note")).isEqualTo("午餐 [订单号]");
    }

    @Test
    void query_filters_and_retry_hint() {
        var filters = new BillFilters("", "", "", "美团", "", "", null, null, "");
        assertThat(bills.query(filters, 1, 50, "alice")).satisfies(result -> {
            assertThat(result.get("total")).isEqualTo(2);
        });
        var miss = new BillFilters("", "", "", "", "", "", null, null, "不存在的关键词");
        assertThat(bills.query(miss, 1, 50, "alice").get("retry_hint")).asString()
            .startsWith("当前筛选未命中数据");
        var amountRange = new BillFilters("", "", "", "", "", "", 50.0, 100.0, "");
        assertThat(bills.query(amountRange, 1, 50, "alice").get("total")).isEqualTo(1);
        var dateOnly = new BillFilters("2026-03-02", "2026-03-02", "", "", "", "", null, null, "");
        assertThat(bills.query(dateOnly, 1, 50, "alice").get("total")).isEqualTo(2);
    }

    @Test
    void query_pagination() {
        var page1 = bills.query(BillFilters.EMPTY, 1, 2, "alice");
        assertThat(page1.get("total")).isEqualTo(4);
        assertThat((List<?>) page1.get("items")).hasSize(2);
        assertThat(page1.get("page")).isEqualTo(1);
        var page2 = bills.query(BillFilters.EMPTY, 2, 2, "alice");
        assertThat((List<?>) page2.get("items")).hasSize(2);
    }

    @Test
    void overview_aggregates_and_scopes() {
        Map<String, Object> alice = bills.overview(BillFilters.EMPTY, "alice");
        assertThat(alice.get("total_amount")).isEqualTo(335.30);
        assertThat(alice.get("count")).isEqualTo(4L);
        assertThat(alice.get("pending")).isEqualTo(1);
        assertThat((String) alice.get("data_from")).isEqualTo("2026-02-28");
        assertThat((String) alice.get("data_to")).isEqualTo("2026-03-02");
        assertThat(alice.get("data_note")).isNull();
        List<Map<String, Object>> byCategory = (List<Map<String, Object>>) (List<?>) alice
            .get("by_category");
        // 未分类(京东 199)金额最大排首位;餐饮 124.30 次之
        assertThat(byCategory.get(0)).containsEntry("name", "未分类");
        assertThat(byCategory.get(1)).containsEntry("name", "餐饮")
            .containsEntry("amount", new java.math.BigDecimal("124.30"));
        Map<String, Object> options = (Map<String, Object>) alice.get("options");
        assertThat((List<String>) (List<?>) options.get("methods")).containsExactly("支付宝");
    }

    @Test
    void overview_empty_states() {
        Map<String, Object> emptyOwner = bills.overview(BillFilters.EMPTY, "ghost");
        assertThat(emptyOwner.get("data_note")).isEqualTo("账单库为空(无任何交易记录),请先导入 CSV");
        var filtered = new BillFilters("", "", "不存在的类别", "", "", "", null, null, "");
        Map<String, Object> filteredOut = bills.overview(filtered, "alice");
        assertThat(filteredOut.get("data_note")).isEqualTo("当前筛选条件下无交易记录");
        assertThat(filteredOut.get("data_from")).isNull();
    }

    @Test
    void compare_anchors_on_owner_latest_date() {
        Map<String, Object> result = bills.compare(7, "alice");
        Map<String, Object> current = (Map<String, Object>) result.get("current_period");
        assertThat(current.get("to")).isEqualTo("2026-03-02");   // alice 的最新日期,非 bob/n1 的 03-03
        assertThat(current.get("from")).isEqualTo("2026-02-24");
        List<Map<String, Object>> byCategory = (List<Map<String, Object>>) (List<?>) result
            .get("by_category");
        // 当前窗口(02-24..03-02)含 t4(京东 199,未分类)与餐饮 124.30
        assertThat(byCategory.get(0)).containsEntry("name", "未分类")
            .containsEntry("current", 199.0);
        assertThat(byCategory.get(1)).containsEntry("name", "餐饮")
            .containsEntry("current", 124.30);
    }

    @Test
    void query_result_amounts_are_plain_numbers() {
        var result = bills.query(BillFilters.EMPTY, 1, 10, "alice");
        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) result.get("items");
        assertThat(items.get(0).get("amount")).isInstanceOf(Double.class);
    }
}
