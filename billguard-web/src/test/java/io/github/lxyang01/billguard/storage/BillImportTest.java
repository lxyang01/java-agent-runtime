package io.github.lxyang01.billguard.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.billguard.PgTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CSV 导入:中文表头别名、日期规整、去重、错误收集、默认类别播种。 */
class BillImportTest extends PgTestBase {

    BillRepository bills;

    @BeforeEach
    void init() {
        bills = new BillRepository(jdbc);
    }

    @Test
    void import_bills_with_chinese_headers_and_keyword_categorization() {
        bills.ensureUserCategories("alice");
        var result = bills.importBills("账单.csv", """
            交易编号,交易时间,商户,类别,金额,支付方式,备注
            T-001,2026/03/01 08:05:00,美团外卖,餐饮,35.5,支付宝,午餐
            T-002,2026-03-02T21:00:00Z,京东,,199.0,微信,年货
            T-003,2026-03-03,美团外卖,,30,支付宝,早餐
            """, "alice");
        assertThat(result.get("imported_rows")).isEqualTo(3);
        assertThat(result.get("status")).isEqualTo("completed");
        assertThat(result.get("errors")).isEqualTo(List.of());

        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>)
            bills.query(io.github.lxyang01.billguard.bills.BillFilters.EMPTY, 1, 10, "alice")
                .get("items");
        // 关键词规则:美团外卖 → 餐饮;无命中 → 其他
        Map<String, Object> t1 = items.stream()
            .filter(i -> "T-001".equals(i.get("tx_id"))).findFirst().orElseThrow();
        assertThat(t1.get("category")).isEqualTo("餐饮");
        assertThat(t1.get("paid_at")).isEqualTo("2026-03-01 08:05:00");
        Map<String, Object> t2 = items.stream()
            .filter(i -> "T-002".equals(i.get("tx_id"))).findFirst().orElseThrow();
        assertThat(t2.get("paid_at")).isEqualTo("2026-03-02 21:00:00");
        assertThat(t2.get("category")).isEqualTo("购物");   // 关键词"京东"命中默认购物类
        Map<String, Object> t3 = items.stream()
            .filter(i -> "T-003".equals(i.get("tx_id"))).findFirst().orElseThrow();
        assertThat(t3.get("paid_at")).isEqualTo("2026-03-03 00:00:00");
    }

    @Test
    void duplicate_rows_counted_not_reimported() {
        String csv = """
            tx_id,paid_at,merchant,amount
            D-1,2026-03-01 10:00:00,商户X,10.00
            D-1,2026-03-01 10:00:00,商户X,10.00
            """;
        var first = bills.importBills("dup.csv", csv, "alice");
        assertThat(first.get("imported_rows")).isEqualTo(1);
        assertThat(first.get("duplicate_rows")).isEqualTo(1);
        var second = bills.importBills("dup.csv", csv, "alice");
        assertThat(second.get("imported_rows")).isEqualTo(0);
        assertThat(second.get("duplicate_rows")).isEqualTo(2);
        // 同号不同 owner 各自入库
        var bob = bills.importBills("dup.csv", csv, "bob");
        assertThat(bob.get("imported_rows")).isEqualTo(1);
    }

    @Test
    void bad_rows_collected_with_line_numbers() {
        var result = bills.importBills("bad.csv", """
            tx_id,paid_at,merchant,amount
            OK-1,2026-03-01 10:00:00,好商户,10.00
            BAD-1,不是日期,商户,10.00
            BAD-2,,商户,10.00
            BAD-3,2026-03-01 10:00:00,,10.00
            """, "alice");
        assertThat(result.get("imported_rows")).isEqualTo(1);
        assertThat(result.get("failed_rows")).isEqualTo(3);
        assertThat((List<String>) result.get("errors")).hasSize(3);
        assertThat((String) ((List<?>) result.get("errors")).get(0)).contains("第 3 行");
        assertThat(result.get("status")).isEqualTo("partial");
    }

    @Test
    void missing_required_headers_rejected() {
        assertThatThrownBy(() -> bills.importBills("x.csv", "foo,bar\n1,2", "alice"))
            .hasMessageStartingWith("CSV 缺少必要字段：");
        assertThatThrownBy(() -> bills.importBills("x.csv", "   ", "alice"))
            .hasMessage("CSV 内容为空");
    }

    @Test
    void bom_prefix_tolerated() {
        var result = bills.importBills("bom.csv",
            "﻿tx_id,paid_at,merchant,amount\nB-1,2026-03-01 09:00:00,商户B,5.00",
            "alice");
        assertThat(result.get("imported_rows")).isEqualTo(1);
    }

    @Test
    void subscriptions_import_with_cycle_validation() {
        var ok = bills.importSubscriptions("subs.csv", """
            名称,商户,周期,预期金额
            音乐会员,音乐订阅,月,25.00
            """, "alice");
        assertThat(ok.get("imported_rows")).isEqualTo(1);
        var duplicate = bills.importSubscriptions("subs.csv", """
            名称,商户,周期,预期金额
            音乐会员,音乐订阅,月,25.00
            """, "alice");
        assertThat(duplicate.get("duplicate_rows")).isEqualTo(1);
        var badCycle = bills.importSubscriptions("subs.csv", """
            name,merchant,cycle,expected_amount
            周刊,报刊,周,10.00
            """, "alice");
        assertThat(badCycle.get("failed_rows")).isEqualTo(1);
        assertThat((String) ((List<?>) badCycle.get("errors")).get(0)).contains("周期必须是 月 或 年");
    }

    @Test
    void default_categories_seeded_once_per_owner() {
        bills.ensureUserCategories("alice");
        bills.ensureUserCategories("alice");   // 幂等:不重复播种
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM categories WHERE owner = 'alice'", Integer.class);
        assertThat(count).isEqualTo(6);
        // 默认类别带 owner 戳,不影响他人与存量
        Integer legacy = jdbc.queryForObject(
            "SELECT COUNT(*) FROM categories WHERE owner IS NULL", Integer.class);
        assertThat(legacy).isZero();
    }
}
