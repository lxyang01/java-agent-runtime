package io.github.lxyang01.billguard.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.billguard.PgTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 四类异常检测:阈值、锚点窗口、detail 文案与排序(对齐 bills.py)。 */
class BillAnomaliesTest extends PgTestBase {

    BillRepository bills;
    BillAnomalies anomalies;

    @BeforeEach
    void init() {
        bills = new BillRepository(jdbc);
        anomalies = new BillAnomalies(jdbc, bills);
        seed();
    }

    private void seed() {
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
            + "VALUES ('餐饮', '[]', TRUE, '2026-01-01 00:00:00+00:00', 'alice')");
        jdbc.update("INSERT INTO categories(name, keywords, enabled, created_at, owner) "
            + "VALUES ('零售', '[]', TRUE, '2026-01-01 00:00:00+00:00', 'alice')");
        Long food = jdbc.queryForObject(
            "SELECT id FROM categories WHERE name='餐饮' AND owner='alice'", Long.class);
        Long retail = jdbc.queryForObject(
            "SELECT id FROM categories WHERE name='零售' AND owner='alice'", Long.class);

        // spike:本期(03-08..03-14)餐饮 300,上期(03-01..03-07)餐饮 60
        tx("s-prev", "2026-03-02 10:00:00", "美团", food, "60.00");
        tx("a1", "2026-03-10 10:00:00", "美团", food, "150.00");
        tx("a2", "2026-03-12 20:00:00", "美团", food, "150.00");
        // duplicate:同商户同金额,间隔 2 小时
        tx("d1", "2026-03-13 08:00:00", "视频会员", null, "30.00");
        tx("d2", "2026-03-13 10:00:00", "视频会员", null, "30.00");
        // price_hike:订阅预期 25,窗口内实扣 39
        tx("p1", "2026-03-11 09:00:00", "音乐订阅", null, "39.00");
        jdbc.update("INSERT INTO subscriptions(name, merchant, cycle, expected_amount, active, "
            + "note, created_at, owner) VALUES ('音乐会员','音乐订阅','月', 25.00, TRUE, '', "
            + "'2026-01-01 00:00:00+00:00', 'alice')");
        // outlier:零售 6 笔 —— 5×20 + 1×500 → 均值 100,500 为 5 倍且 ≥200
        for (int i = 1; i <= 5; i++) {
            tx("o-small-" + i, String.format("2026-03-09 12:0%d:00", i), "零售小单", retail,
                "20.00");
        }
        tx("o-big", "2026-03-14 13:00:00", "零售大单", retail, "500.00");
    }

    private void tx(String txId, String paidAt, String merchant, Long categoryId, String amount) {
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, category_id, "
                + "amount, method, status, created_at, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
            txId, paidAt, merchant, "", categoryId, new BigDecimal(amount), "支付宝", "正常",
            "2026-03-15 00:00:00+00:00", "alice");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String dimension) {
        Map<String, Object> result = anomalies.anomalies(7, dimension, 10, "alice");
        return (List<Map<String, Object>>) (List<?>) result.get("items");
    }

    @Test
    void invalid_dimension_rejected() {
        assertThatThrownBy(() -> anomalies.anomalies(7, "weird", 10, "alice"))
            .hasMessage("dimension 必须是 spike、duplicate、price_hike 或 outlier");
    }

    @Test
    void empty_owner_returns_null_periods() {
        Map<String, Object> result = anomalies.anomalies(7, "spike", 10, "ghost");
        assertThat(result.get("current_period")).isNull();
        assertThat((List<?>) result.get("items")).isEmpty();
        assertThat((Map<String, Object>) result.get("thresholds"))
            .containsEntry("spike_ratio", 2.0).containsEntry("spike_min", 100.0);
    }

    @Test
    void spike_detects_category_surge_with_exact_detail() {
        var spike = items("spike");
        assertThat(spike).hasSize(2);
        // 零售(600,上期 0)金额居首;餐饮(300 vs 60)次之
        assertThat(spike.get(0).get("name")).isEqualTo("零售");
        Map<String, Object> food = spike.get(1);
        assertThat(food.get("name")).isEqualTo("餐饮");
        assertThat(food.get("detail")).isEqualTo("本期 ¥300,上期 ¥60,达到 2 倍");
        Map<String, Object> evidence = (Map<String, Object>) food.get("evidence");
        assertThat(evidence).containsEntry("current_amount", 300.0)
            .containsEntry("previous_amount", 60.0);
    }

    @Test
    void duplicate_detects_groups_sorted_by_size() {
        var duplicates = items("duplicate");
        // 三组都满足"同商户同金额且间隔≤3天":零售小单(5 笔)、美团(2)、视频会员(2)
        assertThat(duplicates).hasSize(3);
        Map<String, Object> biggest = duplicates.get(0);
        assertThat(biggest.get("name")).isEqualTo("零售小单 ¥20");
        assertThat(biggest.get("detail")).isEqualTo("5 笔最近间隔 1 分钟");
        Map<String, Object> video = duplicates.stream()
            .filter(i -> String.valueOf(i.get("name")).startsWith("视频会员"))
            .findFirst().orElseThrow();
        assertThat(video.get("detail")).isEqualTo("2 笔最近间隔 120 分钟");
        Map<String, Object> evidence = (Map<String, Object>) video.get("evidence");
        assertThat((List<String>) (List<?>) evidence.get("tx_ids"))
            .containsExactly("d1", "d2");
        assertThat(evidence.get("min_gap_days")).isEqualTo(0.0833);
    }

    @Test
    void price_hike_compares_subscription_expected_amount() {
        var hikes = items("price_hike");
        assertThat(hikes).hasSize(1);
        Map<String, Object> item = hikes.get(0);
        assertThat(item.get("name")).isEqualTo("音乐会员");
        assertThat(item.get("detail")).isEqualTo("预期 ¥25 实扣 ¥39,上涨");
        Map<String, Object> evidence = (Map<String, Object>) item.get("evidence");
        assertThat(evidence).containsEntry("expected_amount", 25.0)
            .containsEntry("actual_amount", 39.0);
    }

    @Test
    void outlier_detects_category_mean_multiple() {
        var outliers = items("outlier");
        assertThat(outliers).hasSize(1);
        Map<String, Object> item = outliers.get(0);
        assertThat(item.get("name")).isEqualTo("零售大单");
        assertThat(item.get("detail")).isEqualTo("¥500 为类别均值 5.0 倍");
        Map<String, Object> evidence = (Map<String, Object>) item.get("evidence");
        assertThat(evidence).containsEntry("amount", 500.0).containsEntry("category_mean", 100.0);
    }

    @Test
    void period_windows_anchored_on_latest_date() {
        Map<String, Object> result = anomalies.anomalies(7, "spike", 10, "alice");
        Map<String, Object> current = (Map<String, Object>) result.get("current_period");
        Map<String, Object> previous = (Map<String, Object>) result.get("previous_period");
        assertThat(current).containsEntry("from", "2026-03-08").containsEntry("to", "2026-03-14");
        assertThat(previous).containsEntry("from", "2026-03-01").containsEntry("to", "2026-03-07");
    }

    @Test
    void limit_clamped_to_max_30() {
        Map<String, Object> result = anomalies.anomalies(90, "outlier", 999, "alice");
        assertThat(((List<?>) result.get("items")).size()).isLessThanOrEqualTo(30);
    }
}
