package io.github.lxyang01.billguard.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.agent.types.RunEvent;
import io.github.lxyang01.agent.types.RunEvents;
import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.storage.PgEvidenceStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 证据构建(事件→条目,去重截断)与 PG 证据存储 upsert。 */
class EvidenceTest extends PgTestBase {

    @Test
    void builds_evidence_from_tool_end_events() {
        var events = List.of(
            RunEvent.of(RunEvents.TOOL_END, "t1", "s1", 1, Map.of(
                "tool", "bill_overview",
                "result", Map.of("by_category", List.of(
                    Map.of("name", "餐饮", "amount", 124.3, "count", 2),
                    Map.of("name", "交通", "amount", 12, "count", 1))))),
            RunEvent.of(RunEvents.TOOL_END, "t1", "s1", 2, Map.of(
                "tool", "bill.aggregate",
                "result", Map.of("by_category", List.of(
                    Map.of("name", "餐饮", "amount", 124.3, "count", 2))))),   // filters 重复 → 去重
            RunEvent.of(RunEvents.TOOL_END, "t1", "s1", 3, Map.of(
                "tool", "bill_compare",
                "result", Map.of("current_period",
                    Map.of("from", "2026-03-01", "to", "2026-03-07", "total", 136.3)))),
            RunEvent.of(RunEvents.TOOL_END, "t1", "s1", 4, Map.of(
                "tool", "bill_samples",
                "result", Map.of("samples", List.of(
                    Map.of("merchant", "美团", "amount", 35.5),
                    Map.of("merchant", "美团", "amount", 88.8))))),   // 同商户 → filters 去重
            RunEvent.of(RunEvents.MODEL_OUTPUT, "t1", "s1", 5, Map.of()));   // 非工具事件忽略

        var evidence = EvidenceBuilder.build(events);
        assertThat(evidence).hasSize(4);   // 餐饮 + 交通 + 周期 + 美团样本
        assertThat(evidence.get(0)).containsEntry("label", "餐饮")
            .containsEntry("description", "¥124.3 · 2 笔");
        assertThat(evidence.get(2)).containsEntry("label", "查看本周期支出")
            .containsEntry("description", "¥136.3");
        assertThat(evidence.get(3)).containsEntry("label", "美团")
            .containsEntry("description", "¥35.5");
    }

    @Test
    void caps_at_twelve_entries() {
        // 单事件样本最多取 10(Python source[:10]);两事件合计 20 → 总量截断 12
        java.util.List<Object> first = new java.util.ArrayList<>();
        java.util.List<Object> second = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            first.add(Map.of("merchant", "商户A" + i, "amount", 10));
            second.add(Map.of("merchant", "商户B" + i, "amount", 10));
        }
        var events = List.of(
            RunEvent.of(RunEvents.TOOL_END, "t", "s", 1,
                Map.of("tool", "bill_search", "result", Map.of("items", first))),
            RunEvent.of(RunEvents.TOOL_END, "t", "s", 2,
                Map.of("tool", "bill_samples", "result", Map.of("samples", second))));
        assertThat(EvidenceBuilder.build(events)).hasSize(12);
    }

    @Test
    void evidence_store_roundtrip_and_upsert() {
        PgEvidenceStore store = new PgEvidenceStore(jdbc);
        store.save("s1", "答案A", List.of(Map.of("label", "餐饮")));
        store.save("s1", "答案A", List.of(Map.of("label", "餐饮", "extra", 1)));   // 同答案覆盖
        store.save("s1", "答案B", List.of(Map.of("label", "交通")));
        var loaded = store.load("s1");
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(PgEvidenceStore.answerKey("答案A")))
            .singleElement().satisfies(e -> assertThat(e).containsEntry("extra", 1));
        assertThat(store.delete("s1")).isEqualTo(2);
        assertThat(store.load("s1")).isEmpty();
    }
}
