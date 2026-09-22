package io.github.lxyang01.agent.engine;

import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.store.ApprovalStore;
import io.github.lxyang01.agent.store.ConversationStore;
import io.github.lxyang01.agent.store.TraceWriter;
import io.github.lxyang01.agent.testing.InMemoryApprovalStore;
import io.github.lxyang01.agent.testing.InMemoryConversationStore;
import io.github.lxyang01.agent.testing.InMemoryTraceWriter;
import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 引擎测试共享夹具:demo 工具集 + 内存三件套 + 临时技能目录。 */
final class EngineHarness {

    final InMemoryConversationStore conversations = new InMemoryConversationStore();
    final InMemoryTraceWriter trace = new InMemoryTraceWriter();
    final InMemoryApprovalStore approvals = new InMemoryApprovalStore();
    final ToolRegistry tools = demoTools();

    AgentRuntime.Builder builder(AgentSpec spec, io.github.lxyang01.agent.llm.LlmClient llm) {
        return AgentRuntime.builder(spec, llm, tools, conversations, trace);
    }

    AgentRuntime runtime(AgentSpec spec, io.github.lxyang01.agent.llm.LlmClient llm,
                         ApprovalStore approvalStore) {
        return builder(spec, llm).approvals(approvalStore).build();
    }

    ConversationStore conversations() {
        return conversations;
    }

    TraceWriter trace() {
        return trace;
    }

    AgentRuntime runtime(AgentSpec spec, io.github.lxyang01.agent.llm.LlmClient llm) {
        return builder(spec, llm).build();
    }

    static ToolRegistry demoTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolDefinition("demo.lookup", "查询",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            io.github.lxyang01.agent.policy.ToolPolicy.read(),
            args -> Map.of("count", 3, "rows", List.of("a", "b", "c")),
            result -> "已查询 3 笔"));
        registry.register(ToolDefinition.of("bill.query", "受限查询(limit 别名角色)",
            Map.of("type", "object",
                "properties", Map.of("limit", Map.of("type", "integer")),
                "required", List.of("limit"), "additionalProperties", false),
            args -> Map.of("rows", List.of("r1"))));
        registry.register(ToolDefinition.of("demo.big", "大结果",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            args -> Map.of("data", "x".repeat(5000))));
        registry.register(new ToolDefinition("demo.write", "高风险写",
            Map.of("type", "object",
                "properties", Map.of("value", Map.of("type", "string")),
                "required", List.of("value"), "additionalProperties", false),
            ToolPolicy.of(RiskLevel.HIGH_WRITE, false, "高风险写操作"),
            args -> Map.of("written", args.get("value"), "storage_path", "demo://out"),
            result -> "已写入 " + ((Map<?, ?>) result).get("written")));
        registry.register(new ToolDefinition("demo.degraded", "会降级的写",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            ToolPolicy.of(RiskLevel.HIGH_WRITE, false, "高风险写操作"),
            args -> Map.of("degraded", true, "error", "circuit open"), null));
        registry.register(new ToolDefinition("bill.aggregate", "聚合(MCP 演示)",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            ToolPolicy.read(),
            args -> Map.of("total", 100), null));
        return registry;
    }

    /** 临时技能目录:demo-flow(触发词"流程",要求先成功 demo.write)。 */
    static Path tempSkills(Path tmp) throws IOException {
        Path root = tmp.resolve("skills");
        Path dir = root.resolve("demo-flow");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: demo-flow\ndescription: 演示流程\n---\n按流程办事。");
        Files.writeString(root.resolve("routes.json"), """
            {"routes":[{"skill":"demo-flow","triggers":["流程"],
              "allowed_tools":["demo.lookup","demo.write"],
              "completion_rules":[{"triggers":["流程"],"required_tools":["demo.write"]}]}]}
            """);
        return root;
    }

    EngineHarness() {}
}
