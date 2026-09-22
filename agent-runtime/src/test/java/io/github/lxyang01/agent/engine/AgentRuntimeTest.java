package io.github.lxyang01.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.skill.SkillRuntime;
import io.github.lxyang01.agent.skill.SkillRuntimeTest;
import io.github.lxyang01.agent.testing.FinalLlm;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.agent.types.AgentStatuses;
import io.github.lxyang01.agent.types.RunEvents;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Task 11:核心循环与 final 三连门禁。 */
class AgentRuntimeTest {

    EngineHarness harness = new EngineHarness();

    AgentSpec spec = AgentSpec.builder("demo", "测试指令",
        List.of("demo.lookup", "bill.query", "demo.big", "demo.write", "demo.degraded",
            "bill.aggregate")).build();

    @Test
    void direct_final_completes() {
        var runtime = harness.runtime(spec, new FinalLlm("你好,一切正常"));
        var response = runtime.run("s1", "在吗");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        assertThat(response.answer()).isEqualTo("你好,一切正常");
        assertThat(response.steps()).isEqualTo(1);
        assertThat(response.activeSkills()).isEmpty();
    }

    @Test
    void tool_then_final_decorates_summary() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.lookup", "arguments", Map.of())),
            Map.of("final", "查询完成"));
        var response = harness.runtime(spec, llm).run("s1", "查一下");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        assertThat(response.steps()).isEqualTo(2);
        assertThat(response.answer()).startsWith("查询完成");
        assertThat(response.answer()).contains("本轮实际执行结果：");
    }

    @Test
    void empty_input_rejected() {
        assertThatThrownBy(() -> harness.runtime(spec, new FinalLlm("x")).run("s1", "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("user input cannot be empty");
    }

    @Test
    void unregistered_spec_tool_rejected_at_build() {
        var bad = AgentSpec.builder("demo", "i", List.of("demo.lookup", "ghost")).build();
        assertThatThrownBy(() -> harness.builder(bad, new FinalLlm("x")).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("AgentSpec references unregistered tools: ghost");
    }

    @Test
    void unknown_tool_becomes_error_result_then_continue() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.missing", "arguments", Map.of())),
            Map.of("final", "继续完成"));
        var response = harness.runtime(spec, llm).run("s1", "试试");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        var toolMessages = harness.conversations.load("s1").messages().stream()
            .filter(m -> m.role().wire().equals("tool")).toList();
        assertThat(toolMessages).singleElement()
            .satisfies(m -> assertThat(m.content()).contains("unknown tool: demo.missing"));
    }

    @Test
    void argument_contract_blocks_out_of_bound_call() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "bill.query",
                "arguments", Map.of("limit", 5))),
            Map.of("tool_call", Map.of("name", "bill.query",
                "arguments", Map.of("limit", 1))),
            Map.of("final", "受限完成"));
        var response = harness.runtime(spec, llm).run("s1", "最多返回 1 条");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        assertThat(harness.trace.records().stream()
            .anyMatch(r -> RunEvents.ARGUMENT_BLOCKED.equals(r.get("event")))).isTrue();
    }

    @Test
    void pii_redacted_in_final() {
        var runtime = harness.runtime(spec, new FinalLlm("联系 13800138000 处理"));
        var response = runtime.run("s1", "帮我处理");
        assertThat(response.answer()).isEqualTo("联系 [手机号] 处理");
        assertThat(harness.trace.records().stream()
            .anyMatch(r -> RunEvents.OUTPUT_REDACTED.equals(r.get("event")))).isTrue();
    }

    @Test
    void max_steps_aborts_with_message() {
        Object[] calls = new Object[8];
        for (int i = 0; i < 8; i++) {
            calls[i] = Map.of("tool_call",
                Map.of("name", "demo.lookup", "arguments", Map.of()));
        }
        var response = harness.runtime(spec, new ScriptedLlm(calls)).run("s1", "反复查");
        assertThat(response.status()).isEqualTo(AgentStatuses.FAILED);
        assertThat(response.answer()).contains("已达到最大执行步数（8）");
        assertThat(response.steps()).isEqualTo(8);
    }

    @Test
    void malformed_model_output_fails_run() {
        var response = harness.runtime(spec, new ScriptedLlm("这不是 JSON")).run("s1", "问");
        assertThat(response.status()).isEqualTo(AgentStatuses.FAILED);
        assertThat(response.answer()).contains("Agent 执行模型步骤失败");
    }

    @Test
    void tool_result_truncated_in_context() {
        var tiny = AgentSpec.builder("demo", "i", spec.toolNames())
            .toolResultContextLimit(10).build();
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.big", "arguments", Map.of())),
            Map.of("final", "略"));
        var response = harness.runtime(tiny, llm).run("s1", "大数据");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        assertThat(harness.conversations.load("s1").messages().stream()
            .filter(m -> m.role().wire().equals("tool"))
            .anyMatch(m -> m.content().contains("已截断至 10 字符"))).isTrue();
    }

    @Test
    void final_gates_completion_then_sections() {
        // 真实技能:月报(需先 bill.aggregate;输出四章契约)
        SkillRuntime skills = new SkillRuntime(SkillRuntimeTest.skillsRoot());
        var llm = new ScriptedLlm(
            Map.of("final", "初稿"),                                            // 缺必做工具 → completion_blocked
            Map.of("tool_call", Map.of("name", "bill.aggregate", "arguments", Map.of())),
            Map.of("final", "## 支出事实\n总 100"),                             // 缺章节 → output_contract_blocked
            Map.of("final", "## 支出事实\n总 100\n## 异常清单\n无\n## 根因推测\n无\n## 行动计划\n无"));
        var runtime = harness.builder(spec, llm).skills(skills).build();
        var response = runtime.run("s1", "给我一份守卫报告");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        var events = harness.trace.records().stream().map(r -> r.get("event")).toList();
        assertThat(events).contains(RunEvents.COMPLETION_BLOCKED,
            RunEvents.OUTPUT_CONTRACT_BLOCKED);
    }

    @Test
    void unsupported_number_blocked_until_evidence() {
        var llm = new ScriptedLlm(
            Map.of("final", "总额 250 元"),                                     // 无证据数字 → grounding_blocked
            Map.of("tool_call", Map.of("name", "bill.aggregate", "arguments", Map.of())),
            Map.of("final", "总额 100 元"));                                    // 有工具证据
        var response = harness.runtime(spec, llm).run("s1", "总额多少");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        assertThat(response.answer()).isEqualTo("总额 100 元");
        assertThat(harness.trace.records().stream()
            .anyMatch(r -> RunEvents.GROUNDING_BLOCKED.equals(r.get("event")))).isTrue();
    }
}
