package io.github.lxyang01.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.util.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SkillRuntimeTest {

    public static final Path TEST_SKILLS = skillsRoot();

    /** 共享测试资产路径(其他测试包复用真实 4 技能)。 */
    public static Path skillsRoot() {
        try {
            return Path.of(SkillRuntimeTest.class.getResource("/skills").toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test skills resource missing", e);
        }
    }

    SkillRuntime runtime = new SkillRuntime(TEST_SKILLS);

    SkillRuntimeTest() throws Exception {}

    @Test
    void no_trigger_falls_back_to_default_skill() {
        var activations = runtime.activate("你好");
        assertThat(activations).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("bill-triage");
            assertThat(a.reason()).isEqualTo("default");
            assertThat(a.score()).isEqualTo(1);
        });
    }

    @Test
    void explicit_reference_scores_10000() {
        var activations = runtime.activate("请执行 $monthly-guard-report");
        assertThat(activations).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("monthly-guard-report");
            assertThat(a.reason()).isEqualTo("explicit:$monthly-guard-report");
            assertThat(a.score()).isEqualTo(10_000);
        });
    }

    @Test
    void unknown_explicit_reference_throws() {
        assertThatThrownBy(() -> runtime.activate("用 $no-such-skill 处理"))
            .isInstanceOf(SkillException.class)
            .hasMessage("unknown explicitly requested skill: no-such-skill");
    }

    @Test
    void trigger_routes_by_keyword() {
        var activations = runtime.activate("最近有没有重复扣费的异常");
        assertThat(activations).isNotEmpty();
        assertThat(activations.get(0).name()).isEqualTo("anomaly-investigation");
        assertThat(activations.get(0).reason()).startsWith("trigger:");
    }

    @Test
    void co_occurrence_trigger_requires_all_words() {
        // ["取消","订阅"] 共现命中 monthly-guard-report;只出现一个词不命中
        assertThat(runtime.activate("帮我取消这个订阅").get(0).name())
            .isEqualTo("monthly-guard-report");
        assertThat(runtime.activate("取消计划再议").get(0).name()).isEqualTo("bill-triage");
    }

    @Test
    void empty_input_rejected() {
        assertThatThrownBy(() -> runtime.activate("   "))
            .isInstanceOf(SkillException.class)
            .hasMessage("cannot route an empty request");
    }

    @Test
    void version_is_sha256_prefix_of_file_content() throws Exception {
        String raw = Files.readString(TEST_SKILLS.resolve("bill-triage/SKILL.md"));
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        var activations = runtime.activate("账单概览");
        assertThat(activations.get(0).version()).isEqualTo(hex.substring(0, 12));
    }

    @Test
    void instructions_loaded_only_on_activation_with_limits_enforced() {
        var activations = runtime.activate("月报");
        assertThat(activations.get(0).instructions()).isNotBlank();
        assertThat(activations.get(0).instructions()).doesNotStartWith("---");
    }

    @Test
    void completion_rules_resolve_required_tool_groups() {
        // "样本/明细/哪几笔" 命中 anomaly-investigation 的完成规则
        var activations = runtime.activate("异常交易看下明细");
        var anomaly = activations.stream()
            .filter(a -> a.name().equals("anomaly-investigation")).findFirst().orElseThrow();
        assertThat(anomaly.requiredToolGroups()).isNotEmpty();
        assertThat(anomaly.requiredToolPlan()).isNotEmpty();
    }

    @Test
    void output_contract_passed_through() {
        var activations = runtime.activate("给我一份守卫报告");
        var report = activations.stream()
            .filter(a -> a.name().equals("monthly-guard-report")).findFirst().orElseThrow();
        assertThat(report.outputContract()).containsKeys("sections", "gate_terms");
    }

    @Test
    void allowed_tools_intersect_fallback_in_fallback_order() {
        var activations = runtime.activate("重复扣费");
        // anomaly-investigation 的 allowed_tools 不含 bill.aggregate(routes.json 如此),
        // 交集按 fallback 顺序过滤
        var fallback = List.of("bill.aggregate", "bill.query", "bill.get_samples",
            "work-items.prepare_issue", "nope");
        var allowed = SkillRuntime.allowedTools(activations, fallback);
        assertThat(allowed).containsExactly("bill.query", "bill.get_samples");
    }

    @Test
    void empty_intersection_throws() {
        var activations = runtime.activate("重复扣费");
        assertThatThrownBy(() -> SkillRuntime.allowedTools(activations, List.of("nope")))
            .isInstanceOf(SkillException.class)
            .hasMessageContaining("expose no tools allowed by AgentSpec");
    }

    @Test
    void no_skills_directory_throws() {
        assertThatThrownBy(() -> new SkillRuntime(Path.of("no-such-dir")))
            .isInstanceOf(SkillException.class)
            .hasMessageStartingWith("skill root does not exist");
    }

    @Test
    void bad_routes_json_reports_skill_error(@TempDir Path tmp) throws IOException {
        writeSkill(tmp, "alpha", "说明");
        Files.writeString(tmp.resolve("routes.json"), "{\"routes\": \"not-a-list\"}");
        assertThatThrownBy(() -> new SkillRuntime(tmp))
            .isInstanceOf(SkillException.class)
            .hasMessage("skills/routes.json must contain a routes array");
    }

    @Test
    void route_for_unknown_skill_rejected(@TempDir Path tmp) throws IOException {
        writeSkill(tmp, "alpha", "说明");
        Files.writeString(tmp.resolve("routes.json"),
            "{\"routes\":[{\"skill\":\"ghost\",\"triggers\":[\"x\"],\"allowed_tools\":[\"t\"]}]}");
        assertThatThrownBy(() -> new SkillRuntime(tmp))
            .isInstanceOf(SkillException.class)
            .hasMessage("route references an uninstalled skill: ghost");
    }

    @Test
    void skill_missing_route_rejected(@TempDir Path tmp) throws IOException {
        writeSkill(tmp, "alpha", "说明");
        writeSkill(tmp, "beta", "另一个");
        Files.writeString(tmp.resolve("routes.json"),
            "{\"routes\":[{\"skill\":\"alpha\",\"triggers\":[\"x\"],\"allowed_tools\":[\"t\"]}]}");
        assertThatThrownBy(() -> new SkillRuntime(tmp))
            .isInstanceOf(SkillException.class)
            .hasMessage("skills missing routing policy: beta");
    }

    @Test
    void completion_rule_tool_outside_allowed_rejected(@TempDir Path tmp) throws IOException {
        writeSkill(tmp, "alpha", "说明");
        Files.writeString(tmp.resolve("routes.json"), """
            {"routes":[{"skill":"alpha","triggers":["x"],"allowed_tools":["t1"],
              "completion_rules":[{"triggers":["y"],"required_tools":["t2"]}]}]}
            """);
        assertThatThrownBy(() -> new SkillRuntime(tmp))
            .isInstanceOf(SkillException.class)
            .hasMessage("completion rule requires tools outside allowed_tools for alpha: t2");
    }

    @Test
    void oversized_skill_body_rejected(@TempDir Path tmp) throws IOException {
        writeSkill(tmp, "alpha", "说明");
        String big = "x".repeat(8001);
        Files.writeString(tmp.resolve("alpha/SKILL.md"),
            "---\nname: alpha\ndescription: 说明\n---\n" + big);
        Files.writeString(tmp.resolve("routes.json"),
            "{\"routes\":[{\"skill\":\"alpha\",\"triggers\":[\"x\"],\"allowed_tools\":[\"t\"]}]}");
        new SkillRuntime(tmp).refresh();   // 构造合法
        assertThatThrownBy(() -> new SkillRuntime(tmp).activate("x"))
            .isInstanceOf(SkillException.class)
            .hasMessage("skill body exceeds 8000 chars: alpha");
    }

    @Test
    void directory_name_must_match_skill_name(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("wrong-dir"));
        Files.writeString(tmp.resolve("wrong-dir/SKILL.md"),
            "---\nname: alpha\ndescription: 说明\n---\n正文");
        assertThatThrownBy(() -> new SkillRuntime(tmp))
            .isInstanceOf(SkillException.class)
            .hasMessage("skill directory must match name 'alpha': wrong-dir");
    }

    @Test
    void frontmatter_unterminated_rejected(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("alpha"));
        Files.writeString(tmp.resolve("alpha/SKILL.md"), "---\nname: alpha\n正文");
        assertThatThrownBy(() -> new SkillRuntime(tmp))
            .isInstanceOf(SkillException.class)
            .hasMessageContaining("unterminated YAML frontmatter");
    }

    private static void writeSkill(Path root, String name, String description) throws IOException {
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name).resolve("SKILL.md"),
            "---\nname: " + name + "\ndescription: " + description + "\n---\n工作流正文");
    }
}
