package io.github.lxyang01.billguard.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.bills.BillFilters;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 类别规则 / 工作流 / 订阅 / 报告 / 导出 / 清空(对齐 bills.py)。 */
class BillCrudTest extends PgTestBase {

    BillRepository bills;

    @BeforeEach
    void init() {
        bills = new BillRepository(jdbc);
        bills.ensureUserCategories("alice");
        tx("T-1", "2026-03-01 10:00:00", "美团", "35.50");
        tx("T-2", "2026-03-02 11:00:00", "滴滴", "12.00");
    }

    private void tx(String txId, String paidAt, String merchant, String amount) {
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, amount, method, "
                + "status, created_at, owner) VALUES (?,?,?,?,?,'支付宝','正常',?,'alice')",
            txId, paidAt, merchant, "", new BigDecimal(amount),
            "2026-03-03 00:00:00+00:00");
    }

    @Test
    void category_crud_with_audits() {
        var saved = bills.saveCategory("宠物", List.of("宠物店", "猫粮"), true, null, "alice",
            "alice");
        assertThat(saved.get("id")).isNotNull();
        var categories = bills.categories("alice");
        assertThat(categories.stream().anyMatch(
            c -> "宠物".equals(c.get("name")) && ((List<?>) c.get("keywords")).contains("猫粮")))
            .isTrue();

        Long id = ((Number) saved.get("id")).longValue();
        var updated = bills.saveCategory("宠物用品", List.of("猫粮"), false, id, "alice", "alice");
        assertThat(updated.get("name")).isEqualTo("宠物用品");

        var deleted = bills.deleteCategory(id, "alice", "alice");
        assertThat(deleted.get("unassigned")).isEqualTo(0);
        assertThatThrownBy(() -> bills.deleteCategory(id, "alice", "alice"))
            .hasMessage("类别不存在：" + id);

        List<Map<String, Object>> audits = bills.recentAudits(20, "alice");
        assertThat(audits).isNotEmpty();
        assertThat(String.valueOf(audits.get(0).get("action"))).isEqualTo("category");
    }

    @Test
    void save_category_duplicate_name_rejected() {
        bills.saveCategory("出行", List.of("地铁"), true, null, "alice", "alice");
        assertThatThrownBy(() ->
            bills.saveCategory("出行", List.of(), true, null, "alice", "alice"))
            .hasMessage("类别名称已存在：出行");
    }

    @Test
    void rematch_reassigns_by_keywords() {
        bills.saveCategory("打车", List.of("滴滴"), true, null, "alice", "alice");
        var result = bills.rematchCategories("alice", "alice");
        assertThat(result.get("transactions")).isEqualTo(2);
        // T-1 美团→餐饮、T-2 滴滴→交通(默认类先命中),两笔都被重匹配
        assertThat(result.get("changed")).isEqualTo(2);
        Map<String, Object> t2 = ((List<Map<String, Object>>) (List<?>)
            bills.query(BillFilters.EMPTY, 1, 10, "alice").get("items")).stream()
            .filter(i -> "T-2".equals(i.get("tx_id"))).findFirst().orElseThrow();
        assertThat(t2.get("category")).isEqualTo("交通");   // 默认交通类含关键词"滴滴"
    }

    @Test
    void workflow_updates_with_status_validation() {
        var result = bills.updateWorkflow(List.of("T-1", "T-2", "T-X"), "alice", "待核查", "核对",
            "alice");
        assertThat(result.get("updated_tx_ids")).isEqualTo(List.of("T-1", "T-2"));
        assertThat(result.get("count")).isEqualTo(2);
        assertThatThrownBy(() ->
            bills.updateWorkflow(List.of("T-1"), "alice", "奇怪状态", "", "alice"))
            .hasMessage("status 必须是正常、待核查、核查中、已确认、已忽略");
        assertThatThrownBy(() -> bills.updateWorkflow(List.of(), "alice", "正常", "", "alice"))
            .hasMessage("至少选择一条交易");
    }

    @Test
    void transaction_audits_scoped_by_owner() {
        bills.updateWorkflow(List.of("T-1"), "alice", "已确认", "", "alice");
        // bob 名下同号交易 + 审计:alice 不可见(EXISTS + 双 owner 过滤)
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, amount, method, "
                + "status, created_at, owner) VALUES ('T-1','2026-03-01 10:00:00','美团','',"
                + "35.50,'支付宝','正常','2026-03-03 00:00:00+00:00','bob')");
        jdbc.update("INSERT INTO tx_audits(tx_id, action, operator, new_value, changed_at, owner) "
            + "VALUES ('T-1','workflow','bob','{}','2026-03-03 00:00:00+00:00','bob')");
        List<Map<String, Object>> audits = bills.transactionAudits("T-1", 50, "alice");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("operator")).isEqualTo("alice");
    }

    @Test
    void update_transaction_category_creates_missing_category() {
        var result = bills.updateTransactionCategory("T-1", "医疗", "alice", "alice");
        assertThat(result.get("old_category")).isEqualTo("未分类");   // 未分类的交易
        assertThat(result.get("new_category")).isEqualTo("医疗");
        assertThat(bills.categories("alice").stream()
            .anyMatch(c -> "医疗".equals(c.get("name")))).isTrue();
    }

    @Test
    void subscriptions_list_and_toggle() {
        jdbc.update("INSERT INTO subscriptions(name, merchant, cycle, expected_amount, active, "
            + "note, created_at, owner) VALUES ('外卖','美团','月',30.00,TRUE,'',"
            + "'2026-01-01 00:00:00+00:00','alice')");
        List<Map<String, Object>> subs = bills.subscriptions("alice");
        assertThat(subs).hasSize(1);
        assertThat(subs.get(0).get("active")).isEqualTo(true);
        assertThat(subs.get(0).get("last_paid_at")).isEqualTo("2026-03-01 10:00:00");
        var toggled = bills.setSubscriptionActive(1L, false, "alice", "alice");
        assertThat(toggled.get("active")).isEqualTo(false);
        assertThatThrownBy(() -> bills.setSubscriptionActive(99L, true, "alice", "alice"))
            .hasMessage("订阅不存在：99");
    }

    @Test
    void reports_save_list_delete() {
        var saved = bills.saveReport("s-1", "月度报告", "内容", "alice");
        assertThat(saved.get("id")).isNotNull();
        assertThat(bills.reports(10, "alice")).hasSize(1);
        Long id = ((Number) saved.get("id")).longValue();
        var deleted = bills.deleteReport(id, "alice");
        assertThat(((Map<?, ?>) deleted.get("deleted")).get("title")).isEqualTo("月度报告");
        assertThatThrownBy(() -> bills.deleteReport(id, "alice"))
            .hasMessage("报告不存在：" + id);
    }

    @Test
    void export_csv_with_bom_and_header() {
        String csv = bills.exportCsv(BillFilters.EMPTY, "alice");
        assertThat(csv).startsWith("﻿").startsWith("﻿tx_id,paid_at,merchant,category,amount,"
            + "method,note");
        assertThat(csv).contains("T-1").contains("美团");
        assertThat(csv.lines().count()).isEqualTo(3);
    }

    @Test
    void purge_owner_keeps_audit_marker_and_isolates() {
        tx("B-1", "2026-03-05 10:00:00", "他人商户", "9.00");
        jdbc.update("UPDATE transactions SET owner = 'bob' WHERE tx_id = 'B-1'");
        var counts = bills.purgeOwner("alice", "alice", "自助清空");
        assertThat(counts).containsEntry("transactions", 2L);
        // bob 的行不受影响
        Integer bobCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE owner = 'bob'", Integer.class);
        assertThat(bobCount).isEqualTo(1);
        // 留审计标记
        Integer purgeMark = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tx_audits WHERE tx_id = '__purge__'", Integer.class);
        assertThat(purgeMark).isEqualTo(1);
    }
}
