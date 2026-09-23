package io.github.lxyang01.agent.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.agent.skill.SkillRuntimeTest;
import io.github.lxyang01.agent.skill.SkillRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestContractsTest {

    SkillRuntime skills = new SkillRuntime(SkillRuntimeTest.skillsRoot());

    @Test
    void max_rows_with_sample_context_targets_samples_role() {
        // tail 命中"样本" → samples;prefix 命中"搜索" → query 也加入(双角色)
        var contract = RequestContracts.compile("搜索一下,最多返回 5 条样本", List.of());
        assertThat(contract.argumentConstraints()).singleElement().satisfies(c -> {
            assertThat(c.toolRoles()).containsExactly("samples", "query");
            assertThat(c.path()).isEqualTo("limit");
            assertThat(c.operator()).isEqualTo("lte");
            assertThat(c.value()).isEqualTo(5);
            assertThat(c.source()).contains("最多返回 5 条");
        });
    }

    @Test
    void search_context_without_sample_defaults_to_query_role() {
        var contract = RequestContracts.compile("查找商户,最多返回 10 条记录", List.of());
        assertThat(contract.argumentConstraints()).singleElement()
            .satisfies(c -> assertThat(c.toolRoles()).containsExactly("query"));
    }

    @Test
    void max_rows_without_context_defaults_to_query_role() {
        var contract = RequestContracts.compile("最多返回 3 条", List.of());
        assertThat(contract.argumentConstraints()).singleElement()
            .satisfies(c -> assertThat(c.toolRoles()).containsExactly("query"));
    }

    @Test
    void top_items_targets_anomalies_role() {
        var contract = RequestContracts.compile("列出前 3 项", List.of());
        assertThat(contract.argumentConstraints()).singleElement().satisfies(c -> {
            assertThat(c.toolRoles()).containsExactly("anomalies");
            assertThat(c.value()).isEqualTo(3);
        });
    }

    @Test
    void strictest_bound_wins_for_same_role_set() {
        var contract = RequestContracts.compile("最多返回 10 条,再看前 3 项", List.of());
        // 两条约束角色不同(query/anomalies),各自保留
        assertThat(contract.argumentConstraints()).hasSize(2);
        var retest = RequestContracts.compile("最多返回 10 条,最多返回 3 条", List.of());
        assertThat(retest.argumentConstraints()).singleElement()
            .satisfies(c -> assertThat(c.value()).isEqualTo(3));
    }

    @Test
    void violations_reported_with_exact_messages() {
        var contract = RequestContracts.compile("最多返回 5 条", List.of());
        var schema = Map.<String, Object>of("properties", Map.of("limit", Map.of("type", "integer")));

        assertThat(contract.toolViolations("bill.query", Map.of(), schema))
            .containsExactly("参数 limit 必须显式提供，且不能超过 5");
        assertThat(contract.toolViolations("bill.query", Map.of("limit", "many"), schema))
            .containsExactly("参数 limit 必须是数字");
        assertThat(contract.toolViolations("bill.query", Map.of("limit", 7), schema))
            .containsExactly("参数 limit=7 超过用户要求的最大值 5");
        assertThat(contract.toolViolations("bill.query", Map.of("limit", 5), schema)).isEmpty();
    }

    @Test
    void no_limit_property_in_schema_means_no_violations() {
        var contract = RequestContracts.compile("最多返回 5 条", List.of());
        assertThat(contract.toolViolations("bill.query", Map.of(),
            Map.of("properties", Map.of()))).isEmpty();
    }

    @Test
    void tool_role_aliases_cover_local_and_mcp_names() {
        assertThat(RequestContracts.toolRole("bill.get_samples")).isEqualTo("samples");
        assertThat(RequestContracts.toolRole("feedback_samples")).isEqualTo("samples");
        assertThat(RequestContracts.toolRole("bill.query")).isEqualTo("query");
        assertThat(RequestContracts.toolRole("feedback_search")).isEqualTo("query");
        assertThat(RequestContracts.toolRole("bill.detect_anomalies")).isEqualTo("anomalies");
        assertThat(RequestContracts.toolRole("work-items.commit_issue"))
            .isEqualTo("commit_issue");
    }

    @Test
    void sections_from_skill_output_contract_gated_by_terms() {
        var activations = skills.activate("给我一份守卫报告");
        var contract = RequestContracts.compile("给我一份守卫报告", activations);
        assertThat(contract.requiredSections()).hasSize(4);
        assertThat(contract.requiredSections()).extracting(OutputSection::name)
            .containsExactly("支出事实", "异常清单", "根因推测", "行动计划");
    }

    @Test
    void sections_not_gated_without_terms() {
        // "月报" 触发技能但 gate_terms 是 守卫报告/月报/周报/汇报 —— 输入含"月报"也命中
        var activations = skills.activate("帮忙出月报");
        var contract = RequestContracts.compile("帮忙出月报", activations);
        assertThat(contract.requiredSections()).isNotEmpty();
        // 无技能激活 → 无章节契约
        assertThat(RequestContracts.compile("随便聊聊", List.of()).requiredSections()).isEmpty();
    }

    @Test
    void missing_sections_detects_markdown_and_json_labels() {
        var contract = RequestContracts.compile("守卫报告",
            skills.activate("守卫报告"));
        assertThat(contract.missingSections(
            "## 支出事实\nx\n## 异常清单\ny\n## 根因推测\nz\n## 行动计划\nw")).isEmpty();
        assertThat(contract.missingSections(
            "{\"支出事实\":1,\"异常清单\":1,\"根因推测\":1,\"行动计划\":1}")).isEmpty();
        assertThat(contract.missingSections("## 支出事实\nx"))
            .containsExactly("异常清单", "根因推测", "行动计划");
        // JSON 键大小写不敏感(别名 data_facts)
        assertThat(contract.missingSections(
            "{\"data_facts\":1,\"anomalies\":1,\"root_cause\":1,\"action_plan\":1}")).isEmpty();
        // Markdown 别名:加粗行
        assertThat(contract.missingSections("**支出事实**:\nx\n**异常清单**\n**根因推测**\n**行动计划**"))
            .isEmpty();
    }

    @Test
    void prompt_text_renders_constraints_and_sections() {
        // 同一输入须同时携带数量约束与 gate_terms(守卫报告),两条契约才都会编译出来
        var input = "搜索并最多返回 5 条样本,出一份守卫报告";
        var contract = RequestContracts.compile(input, skills.activate(input));
        var text = contract.promptText();
        assertThat(text).contains("动态参数契约：");
        assertThat(text).contains("samples/query.limit <= 5（必须显式传参）");
        assertThat(text).contains("最终输出结构契约：必须包含这些可识别章节：");
    }
}
