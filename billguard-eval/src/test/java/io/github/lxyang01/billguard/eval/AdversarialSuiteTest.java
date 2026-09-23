package io.github.lxyang01.billguard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.agent.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
/**
 * 目录校验 + 报告生成。探针类本身即测试(全绿 = 等价性验收);
 * 本类不重复运行探针(@Suite 会因 Spring 上下文缓存指向已停容器而误报)。
 */
class AdversarialSuiteTest {

    @org.junit.jupiter.api.Test
    void catalog_matches_python_25_probes() throws Exception {
        assertThat(AdversarialCatalog.ALL).hasSize(25);
        // id 连续且无重复
        var ids = AdversarialCatalog.ALL.stream().map(AdversarialCatalog.Entry::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (int i = 1; i <= 25; i++) {
            assertThat(ids).contains(String.format("adv-%03d", i));
        }
        // 报告结构:全部通过时 defense_rate = 1.0
        var report = AdversarialCatalog.report(ids);
        @SuppressWarnings("unchecked")
        var metrics = (java.util.Map<String, Object>) report.get("metrics");
        assertThat(metrics.get("total")).isEqualTo(25);
        assertThat(metrics.get("defense_rate")).isEqualTo(1.0);
        assertThat((String) report.get("scope_note")).contains("Deterministic");
        // 报告样本落盘(供 /api/evaluations 与人工复核)
        Path dir = Path.of("evaluations");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("adversarial-v1-report.json"),
            Json.writePretty(report));
    }
}
