package io.github.lxyang01.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.testing.InMemoryConversationStore;
import io.github.lxyang01.agent.testing.InMemoryTraceWriter;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.agent.types.AgentResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 加固回归:fail-closed 门禁、审计失败即失败、executing 迁移。 */
class HardeningTest {

    private static ToolRegistry writeRegistry(AtomicInteger calls) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolDefinition("danger.write", "W",
            Map.of("type", "object", "properties", Map.of(), "required", List.of(),
                "additionalProperties", false),
            ToolPolicy.of(RiskLevel.HIGH_WRITE, true, "x"),
            args -> {
                calls.incrementAndGet();
                return Map.of("ok", true);
            }, null));
        return registry;
    }

    // #1:高写工具 + 无审批仓 → 构建即拒绝(fail-closed at build)
    @Test
    void build_rejects_gated_tools_without_approval_store() {
        var spec = AgentSpec.builder("a", "i", List.of("danger.write")).maxSteps(1).build();
        var llm = new ScriptedLlm(decisionTool(Map.of()));
        assertThatThrownBy(() -> AgentRuntime.builder(spec, llm, writeRegistry(new AtomicInteger()),
            new InMemoryConversationStore(), new InMemoryTraceWriter()).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("policy-gated tools but no approval store")
            .hasMessageContaining("danger.write");
    }

    // #1:只读工具 + 无审批仓 → 正常构建与运行(读写分离)
    @Test
    void read_only_agent_works_without_approval_store() {
        ToolRegistry reads = new ToolRegistry();
        reads.register(ToolDefinition.of("r", "R",
            Map.of("type", "object", "properties", Map.of(), "required", List.of(),
                "additionalProperties", false),
            args -> Map.of("v", 1)));
        var spec = AgentSpec.builder("a", "i", List.of("r")).maxSteps(2).build();
        var engine = AgentRuntime.builder(spec, new ScriptedLlm(readTool(), decisionFinal("完成")),
            reads, new InMemoryConversationStore(), new InMemoryTraceWriter()).build();
        var response = engine.run("s", "查");
        assertThat(response.status()).isEqualTo("completed");
    }

    private static Map<String, Object> readTool() {
        return Map.of("thought", "t", "tool_call",
            Map.of("name", "r", "arguments", Map.of()));
    }

    private static Map<String, Object> decisionTool(Map<String, Object> args) {
        return Map.of("thought", "t", "tool_call",
            Map.of("name", "danger.write", "arguments", args));
    }

    private static Map<String, Object> decisionFinal(String answer) {
        return Map.of("thought", "t", "final", answer);
    }

    // #4:审计关键事件(approval_pending)写失败 → 操作失败,不执行工具
    @Test
    void audit_write_failure_fails_closed() {
        AtomicInteger calls = new AtomicInteger();
        io.github.lxyang01.agent.store.TraceWriter failing = (sid, tid, agent, record) -> {
            if ("approval_pending".equals(record.get("event"))) {
                throw new IllegalStateException("audit db down");
            }
        };
        var store = new io.github.lxyang01.agent.testing.InMemoryApprovalStore();
        var spec = AgentSpec.builder("a", "i", List.of("danger.write")).maxSteps(1).build();
        var engine = AgentRuntime.builder(spec, new ScriptedLlm(decisionTool(Map.of())),
            writeRegistry(calls), new InMemoryConversationStore(), failing)
            .approvals(store).build();
        assertThatThrownBy(() -> engine.run("s", "写"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("audit-trail write failed");
        assertThat(calls.get()).isZero();
        // 审批暂停是审计记录:写失败即抛(不静默降级),工具未执行 —— fail-closed
    }

    // #4:遥测事件写失败 → 照常完成(降级)
    @Test
    void telemetry_write_failure_degrades() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry reads = new ToolRegistry();
        reads.register(ToolDefinition.of("r", "R",
            Map.of("type", "object", "properties", Map.of(), "required", List.of(),
                "additionalProperties", false),
            args -> Map.of("v", 1)));
        io.github.lxyang01.agent.store.TraceWriter failing = (sid, tid, agent, record) -> {
            if (!"approval_pending".equals(record.get("event"))) {
                throw new IllegalStateException("telemetry db down");
            }
        };
        var spec = AgentSpec.builder("a", "i", List.of("r")).maxSteps(2).build();
        var engine = AgentRuntime.builder(spec, new ScriptedLlm(decisionTool(Map.of()),
            decisionFinal("完成")), reads, new InMemoryConversationStore(), failing).build();
        // decisionTool 指向 danger.write 但 spec 只允许 r → 白名单拒绝 → tool_error 后 final
        AgentResponse response = engine.run("s", "查");
        assertThat(response.status()).isEqualTo("completed");
    }

    // #3:markExecuting 迁移 + executing 状态拒绝重放
    @Test
    void executing_state_rejects_replay_and_narrows_crash_window() {
        var store = new io.github.lxyang01.agent.testing.InMemoryApprovalStore();
        var record = store.request("s", "t", 1, "w", Map.of(),
            ToolPolicy.of(RiskLevel.HIGH_WRITE, true, "x"), Map.of());
        store.decide(record.id(), true, "alice", "");
        store.markExecuting(record.id());
        assertThat(store.get(record.id()).status()).isEqualTo("executing");
        // executing 状态再 markExecuting → 拒
        assertThatThrownBy(() -> store.markExecuting(record.id()))
            .hasMessageContaining("cannot enter executing from executing");
        // executing → executed 走 markExecution(恢复完成路径)
        assertThat(store.markExecution(record.id(), true, "").status()).isEqualTo("executed");
    }
}
