package io.github.lxyang01.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.skill.SkillRuntime;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.agent.types.AgentStatuses;
import io.github.lxyang01.agent.types.RunEvents;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Task 12:三阶段审批暂停/恢复/诚实拒绝。 */
class AgentApprovalFlowTest {

    EngineHarness harness = new EngineHarness();

    AgentSpec spec = AgentSpec.builder("demo", "i",
        List.of("demo.lookup", "demo.write", "demo.degraded")).build();

    private io.github.lxyang01.agent.engine.AgentRuntime runtimeWithApprovals(
        io.github.lxyang01.agent.llm.LlmClient llm) {
        return harness.builder(spec, llm).approvals(harness.approvals).build();
    }

    @Test
    void high_write_pauses_with_checkpoint_v2() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.write",
                "arguments", Map.of("value", "数据"))));
        var response = runtimeWithApprovals(llm).run("s1", "写入数据");
        assertThat(response.status()).isEqualTo(AgentStatuses.APPROVAL_PENDING);
        assertThat(response.approval())
            .containsEntry("tool_name", "demo.write")
            .containsEntry("status", "pending")
            .containsEntry("risk_level", "high_write");
        assertThat(response.answer()).contains("需要人工审批");

        var record = harness.approvals.get((String) response.approval().get("id"));
        var checkpoint = record.checkpoint();
        assertThat(checkpoint.get("schema_version")).isEqualTo(2);
        assertThat(checkpoint).containsKeys("full_tool_payloads", "session_id", "trace_id",
            "user_input", "step", "call_id", "skill_versions", "allowed_tools",
            "execution_summaries", "artifact_paths", "completed_tools");
    }

    @Test
    void resume_after_approval_executes_and_completes() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.write",
                "arguments", Map.of("value", "数据"))),
            Map.of("final", "已按要求写入"));
        var runtime = runtimeWithApprovals(llm);
        var first = runtime.run("s1", "写入数据");
        var id = (String) first.approval().get("id");

        harness.approvals.decide(id, true, "admin", "同意");
        var done = runtime.resume(id);
        assertThat(done.status()).isEqualTo(AgentStatuses.COMPLETED);
        assertThat(done.answer()).contains("已按要求写入").contains("已写入 数据");
        assertThat(harness.approvals.get(id).status()).isEqualTo("executed");
    }

    @Test
    void resume_rejects_when_not_approved() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.write",
                "arguments", Map.of("value", "x"))));
        var runtime = runtimeWithApprovals(llm);
        var first = runtime.run("s1", "写入");
        assertThatThrownBy(() -> runtime.resume((String) first.approval().get("id")))
            .isInstanceOf(PolicyException.class)
            .hasMessage("approval must be approved before resume: pending");
    }

    @Test
    void resume_detects_skill_version_drift(@TempDir java.nio.file.Path tmp) throws Exception {
        var skillsRoot = EngineHarness.tempSkills(tmp);
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.write",
                "arguments", Map.of("value", "x"))),
            Map.of("final", "完成"));
        var runtime = harness.builder(spec, llm)
            .approvals(harness.approvals)
            .skills(new SkillRuntime(skillsRoot))
            .build();
        var first = runtime.run("s1", "流程演练");
        assertThat(first.status()).isEqualTo(AgentStatuses.APPROVAL_PENDING);
        var id = (String) first.approval().get("id");
        harness.approvals.decide(id, true, "admin", "");

        // SKILL.md 内容变化 → 版本指纹漂移 → 恢复被拒
        Files.writeString(skillsRoot.resolve("demo-flow/SKILL.md"),
            Files.readString(skillsRoot.resolve("demo-flow/SKILL.md")) + "\n新增一行。");
        assertThatThrownBy(() -> runtime.resume(id))
            .isInstanceOf(PolicyException.class)
            .hasMessage("active Skill versions changed while the run was awaiting approval");
    }

    @Test
    void resume_detects_tool_policy_drift() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.write",
                "arguments", Map.of("value", "x"))));
        var runtime = runtimeWithApprovals(llm);
        var first = runtime.run("s1", "写入");
        var id = (String) first.approval().get("id");
        harness.approvals.decide(id, true, "admin", "");
        // 篡改检查点里的 allowed_tools(模拟策略漂移)
        var record = harness.approvals.get(id);
        record.checkpoint().put("allowed_tools", List.of("demo.lookup"));
        assertThatThrownBy(() -> runtime.resume(id))
            .isInstanceOf(PolicyException.class)
            .hasMessage("tool policy changed while the run was awaiting approval");
    }

    @Test
    void finalize_rejection_answers_honestly() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.write",
                "arguments", Map.of("value", "机密"))));
        var runtime = runtimeWithApprovals(llm);
        var first = runtime.run("s1", "写入");
        var id = (String) first.approval().get("id");
        harness.approvals.decide(id, false, "admin", "不需要");
        var response = runtime.finalizeRejection(id);
        assertThat(response.status()).isEqualTo(AgentStatuses.REJECTED);
        assertThat(response.answer())
            .contains("已拒绝高风险操作 `demo.write`，Agent 未执行该工具。")
            .contains("审批说明：不需要");
        // 未执行:会话里没有工具结果消息
        assertThat(harness.conversations.load("s1").messages().stream()
            .noneMatch(m -> m.role().wire().equals("tool"))).isTrue();
    }

    @Test
    void degraded_high_write_counts_as_failure() {
        var llm = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.degraded", "arguments", Map.of())),
            Map.of("final", "服务暂不可用,稍后再试"));
        var runtime = runtimeWithApprovals(llm);
        var first = runtime.run("s1", "写入");
        var id = (String) first.approval().get("id");
        harness.approvals.decide(id, true, "admin", "");
        var done = runtime.resume(id);
        assertThat(done.status()).isEqualTo(AgentStatuses.COMPLETED);   // 对话降级完成
        assertThat(harness.approvals.get(id).status()).isEqualTo("failed"); // 审批如实记失败
        assertThat(harness.trace.records().stream().anyMatch(r ->
            RunEvents.TOOL_ERROR.equals(r.get("event")))).isTrue();
    }

    @Test
    void resume_without_gateway_throws() {
        var runtime = harness.runtime(spec, new ScriptedLlm(Map.of("final", "x")));
        assertThatThrownBy(() -> runtime.resume("POL-XXX"))
            .isInstanceOf(PolicyException.class)
            .hasMessage("this Agent has no Policy Gateway");
    }
}
