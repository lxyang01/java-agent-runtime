package io.github.lxyang01.agent.engine;

import io.github.lxyang01.agent.contract.RequestContract;
import io.github.lxyang01.agent.contract.RequestContracts;
import io.github.lxyang01.agent.guardrail.GuardrailException;
import io.github.lxyang01.agent.guardrail.Guardrails;
import io.github.lxyang01.agent.guardrail.Redaction;
import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.llm.LlmResult;
import io.github.lxyang01.agent.policy.Allowed;
import io.github.lxyang01.agent.policy.ApprovalRequired;
import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.policy.PolicyGateway;
import io.github.lxyang01.agent.policy.PolicyVerdict;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.skill.SkillActivation;
import io.github.lxyang01.agent.skill.SkillException;
import io.github.lxyang01.agent.skill.SkillRuntime;
import io.github.lxyang01.agent.store.ApprovalRecord;
import io.github.lxyang01.agent.store.ApprovalStore;
import io.github.lxyang01.agent.store.ConversationStore;
import io.github.lxyang01.agent.store.TraceWriter;
import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolException;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.agent.types.AgentResponse;
import io.github.lxyang01.agent.types.AgentStatuses;
import io.github.lxyang01.agent.types.ChatMessage;
import io.github.lxyang01.agent.types.ChatRole;
import io.github.lxyang01.agent.types.Conversation;
import io.github.lxyang01.agent.types.RunEvent;
import io.github.lxyang01.agent.types.RunEvents;
import io.github.lxyang01.agent.util.Json;
import io.github.lxyang01.agent.util.Strings;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 可审计 Agent 引擎主循环。逐语义engine.HarnessEngine:
 * 步数上限/时限、JSON 决策协议、final 三连门禁、动态参数契约、
 * 三阶段审批暂停与恢复、确定性历史压缩、事件全量外发。
 */
public final class AgentRuntime {

    private final AgentSpec spec;
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final ConversationStore conversations;
    private final TraceWriter traceWriter;
    private final ContextBuilder contextBuilder;
    private final List<Consumer<RunEvent>> hooks;
    private final SkillRuntime skills;        // null = 不启用技能路由
    private final ApprovalStore approvals;    // null = 不启用审批门禁

    private AgentRuntime(Builder builder) {
        this.spec = builder.spec;
        this.llm = builder.llm;
        this.tools = builder.tools;
        this.conversations = builder.conversations;
        this.traceWriter = builder.traceWriter;
        this.contextBuilder = builder.contextBuilder != null
            ? builder.contextBuilder : new ContextBuilder();
        this.hooks = List.copyOf(builder.hooks);
        this.skills = builder.skills;
        this.approvals = builder.approvals;
        TreeSet<String> missing = new TreeSet<>(spec.toolNames());
        missing.removeAll(tools.names());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "AgentSpec references unregistered tools: " + String.join(", ", missing));
        }
        if (approvals == null) {
            TreeSet<String> gated = new TreeSet<>();
            for (String name : spec.toolNames()) {
                var tool = tools.get(name);
                if (tool.policy().requiresApproval()
                    || tool.policy().riskLevel() == RiskLevel.HIGH_WRITE
                    || tool.policy().riskLevel() == RiskLevel.FORBIDDEN) {
                    gated.add(name);
                }
            }
            if (!gated.isEmpty()) {
                throw new IllegalArgumentException(
                    "AgentSpec contains policy-gated tools but no approval store is "
                    + "configured: " + String.join(", ", gated));
            }
        }
    }

    public static Builder builder(AgentSpec spec, LlmClient llm, ToolRegistry tools,
                                  ConversationStore conversations, TraceWriter traceWriter) {
        return new Builder(spec, llm, tools, conversations, traceWriter);
    }

    public static final class Builder {
        private final AgentSpec spec;
        private final LlmClient llm;
        private final ToolRegistry tools;
        private final ConversationStore conversations;
        private final TraceWriter traceWriter;
        private final List<Consumer<RunEvent>> hooks = new ArrayList<>();
        private ContextBuilder contextBuilder;
        private SkillRuntime skills;
        private ApprovalStore approvals;

        private Builder(AgentSpec spec, LlmClient llm, ToolRegistry tools,
                        ConversationStore conversations, TraceWriter traceWriter) {
            this.spec = spec;
            this.llm = llm;
            this.tools = tools;
            this.conversations = conversations;
            this.traceWriter = traceWriter;
        }

        public Builder skills(SkillRuntime skills) {
            this.skills = skills;
            return this;
        }

        public Builder approvals(ApprovalStore approvals) {
            this.approvals = approvals;
            return this;
        }

        public Builder hook(Consumer<RunEvent> hook) {
            this.hooks.add(hook);
            return this;
        }

        public Builder contextBuilder(ContextBuilder contextBuilder) {
            this.contextBuilder = contextBuilder;
            return this;
        }

        public AgentRuntime build() {
            return new AgentRuntime(this);
        }
    }

    // ---- 对外入口 ----

    public AgentResponse run(String sessionId, String userInput) {
        String input = userInput.strip();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("user input cannot be empty");
        }
        Guardrails.validateUserInput(input);
        Conversation conversation = conversations.load(sessionId);
        if (spec.summaryThreshold() > 0
            && conversation.messages().size() > spec.summaryThreshold()) {
            int before = conversation.messages().size();
            conversation = compressHistory(conversation, spec.summaryKeepRecent());
            conversations.save(conversation);
            emit(RunEvents.HISTORY_COMPRESSED, uuidHex(), sessionId, 0,
                Map.of("before", before, "after", conversation.messages().size()));
        }
        conversation.addMessage(ChatMessage.user(input));
        String traceId = uuidHex();
        Redaction inputRedaction = Guardrails.redactPii(input);
        emit(RunEvents.RUN_START, traceId, sessionId, 0,
            mapOf("input", Strings.truncate(inputRedaction.text(), 2_000),
                "input_redactions", inputRedaction.counts(), "agent", spec.name()));

        List<SkillActivation> activeSkills;
        List<String> allowedTools;
        try {
            Activation activation = activate(input, traceId, sessionId, true);
            activeSkills = activation.skills();
            allowedTools = activation.allowedTools();
        } catch (SkillException e) {
            emit(RunEvents.SKILL_ERROR, traceId, sessionId, 0, Map.of("error", e.getMessage()));
            return finish(conversation, "Skill 路由或加载失败：" + e.getMessage(), 0,
                traceId, sessionId, List.of(), AgentStatuses.FAILED);
        }

        RequestContract contract = RequestContracts.compile(input, activeSkills);
        List<ChatMessage> working = contextBuilder.build(spec, conversation.getSummary(),
            conversation.messages(), activeSkills, contract);
        return loop(conversation, working, traceId, sessionId, input, activeSkills,
            allowedTools, 1, new RunState(), contract);
    }

    public AgentResponse resume(String approvalId) {
        if (approvals == null) {
            throw new PolicyException("this Agent has no Policy Gateway");
        }
        ApprovalRecord approval = approvals.get(approvalId);
        if ("executing".equals(approval.status())) {
            // 崩溃残留的执行中标记:自动重放可能重复副作用,交人工/超时回收
            throw new PolicyException(
                "approval is mid-execution (crash residue); reclaim or verify downstream "
                + "idempotency before retrying: " + approvalId);
        }
        if (!"approved".equals(approval.status())) {
            throw new PolicyException(
                "approval must be approved before resume: " + approval.status());
        }
        Map<String, Object> checkpoint = approval.checkpoint();
        if (!Integer.valueOf(2).equals(checkpoint.get("schema_version"))) {
            throw new PolicyException(
                "approval checkpoint version is no longer supported; rerun the request");
        }
        if (!approval.sessionId().equals(checkpoint.get("session_id"))) {
            throw new PolicyException("checkpoint session does not match approval");
        }
        Conversation conversation = conversations.load(approval.sessionId());
        String userInput = String.valueOf(checkpoint.get("user_input"));
        Activation activation = activate(userInput, approval.traceId(), approval.sessionId(),
            false);
        List<SkillActivation> activeSkills = activation.skills();
        List<String> allowedTools = activation.allowedTools();

        Map<String, String> actualVersions = new LinkedHashMap<>();
        for (SkillActivation activationSkill : activeSkills) {
            actualVersions.put(activationSkill.name(), activationSkill.version());
        }
        Map<String, String> expectedVersions = new LinkedHashMap<>();
        Object stored = checkpoint.get("skill_versions");
        if (stored instanceof Map<?, ?> storedVersions) {
            storedVersions.forEach((key, value) ->
                expectedVersions.put(String.valueOf(key), String.valueOf(value)));
        }
        if (!actualVersions.equals(expectedVersions)) {
            throw new PolicyException(
                "active Skill versions changed while the run was awaiting approval");
        }
        if (!allowedTools.equals(stringList(checkpoint.get("allowed_tools")))) {
            throw new PolicyException(
                "tool policy changed while the run was awaiting approval");
        }
        if (!allowedTools.contains(approval.toolName())) {
            throw new PolicyException(
                "approved tool is no longer allowed: " + approval.toolName());
        }

        RequestContract contract = RequestContracts.compile(userInput, activeSkills);
        List<ChatMessage> working = contextBuilder.build(spec, conversation.getSummary(),
            conversation.messages(), activeSkills, contract);
        RunState state = RunState.fromCheckpoint(checkpoint);
        emit(RunEvents.RUN_RESUME, approval.traceId(), approval.sessionId(), approval.step(),
            mapOf("approval_id", approval.id(), "decided_by", approval.decidedBy()));
        approvals.markExecuting(approvalId);
        ToolExecution execution = executeTool(conversation, working, approval.traceId(),
            approval.sessionId(), approval.step(), approval.toolName(), approval.arguments(),
            String.valueOf(checkpoint.get("call_id")), allowedTools, state);
        approvals.markExecution(approval.id(), execution.succeeded(),
            execution.error() == null ? "" : execution.error());
        if (execution.succeeded()) {
            state.completedTools.add(approval.toolName());
        }
        return loop(conversation, working, approval.traceId(), approval.sessionId(), userInput,
            activeSkills, allowedTools, approval.step() + 1, state, contract);
    }

    public AgentResponse finalizeRejection(String approvalId) {
        if (approvals == null) {
            throw new PolicyException("this Agent has no Policy Gateway");
        }
        ApprovalRecord approval = approvals.get(approvalId);
        if (!"rejected".equals(approval.status())) {
            throw new PolicyException(
                "approval must be rejected before finalization: " + approval.status());
        }
        Conversation conversation = conversations.load(approval.sessionId());
        String answer = "已拒绝高风险操作 `" + approval.toolName() + "`，Agent 未执行该工具。"
            + (approval.decisionNote() != null && !approval.decisionNote().isEmpty()
                ? "\n\n审批说明：" + approval.decisionNote() : "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approval_id", approval.id());
        data.put("tool", approval.toolName());
        data.put("decided_by", approval.decidedBy());
        data.put("note", approval.decisionNote());
        emit(RunEvents.APPROVAL_REJECTED, approval.traceId(), approval.sessionId(),
            approval.step(), data);
        return finish(conversation, answer, approval.step(), approval.traceId(),
            approval.sessionId(), List.of(), AgentStatuses.REJECTED);
    }

    // ---- 主循环 ----

    private AgentResponse loop(Conversation conversation, List<ChatMessage> working,
                               String traceId, String sessionId, String userInput,
                               List<SkillActivation> activeSkills, List<String> allowedTools,
                               int startStep, RunState state, RequestContract contract) {
        long startedAt = System.nanoTime();
        for (int step = startStep; step <= spec.maxSteps(); step++) {
            if (spec.runTimeout() != null
                && Duration.ofNanos(System.nanoTime() - startedAt).compareTo(spec.runTimeout()) > 0) {
                String answer = "已达到最大执行时间（"
                    + gFormat(spec.runTimeout().toNanos() / 1_000_000_000.0)
                    + " 秒），任务被 Harness 安全停止。";
                emit(RunEvents.RUN_TIMEOUT, traceId, sessionId, 0,
                    Map.of("timeout_seconds", gFormat(spec.runTimeout().toNanos() / 1e9)));
                return finish(conversation, decorate(answer, state), step - 1, traceId,
                    sessionId, activeSkills, AgentStatuses.FAILED);
            }
            AgentDecision decision;
            try {
                emit(RunEvents.MODEL_START, traceId, sessionId, step,
                    java.util.Collections.emptyMap());
                long modelStarted = System.nanoTime();
                LlmResult result = llm.complete(
                    new LlmRequest(working, tools.schemas(allowedTools)));
                String raw = result.raw();
                try {
                    Guardrails.validateModelOutput(raw);
                } catch (GuardrailException e) {
                    emit(RunEvents.MODEL_OUTPUT_BLOCKED, traceId, sessionId, step,
                        Map.of("output_chars", Strings.len(raw), "reason", "length_limit"));
                    throw e;
                }
                emit(RunEvents.MODEL_OUTPUT, traceId, sessionId, step, mapOf(
                    "raw", Strings.truncate(raw, 20_000),
                    "latency_ms", round2((System.nanoTime() - modelStarted) / 1e6),
                    "usage", result.usage(),
                    "model", result.model()));
                decision = DecisionParser.parse(raw);
                Map<String, Object> decisionData = new LinkedHashMap<>();
                decisionData.put("thought",
                    decision instanceof ToolCallDecision toolCall ? toolCall.thought()
                        : ((FinalDecision) decision).thought());
                decisionData.put("tool",
                    decision instanceof ToolCallDecision toolCall ? toolCall.tool() : null);
                decisionData.put("final", decision instanceof FinalDecision);
                emit(RunEvents.MODEL_DECISION, traceId, sessionId, step, decisionData);
            } catch (Exception e) {
                String answer = "Agent 执行模型步骤失败：" + messageOf(e);
                emit(RunEvents.RUN_ERROR, traceId, sessionId, step,
                    Map.of("error", messageOf(e)));
                return finish(conversation, decorate(answer, state), step, traceId, sessionId,
                    activeSkills, AgentStatuses.FAILED);
            }

            if (decision instanceof FinalDecision finalDecision) {
                List<String> missingTools = missingRequiredTools(activeSkills,
                    state.completedTools);
                if (!missingTools.isEmpty()) {
                    emit(RunEvents.COMPLETION_BLOCKED, traceId, sessionId, step, mapOf(
                        "missing_tools", missingTools,
                        "proposed_final", Strings.truncate(finalDecision.answer(), 4_000)));
                    working.add(ChatMessage.system(
                        "本轮用户请求尚未完成，不能返回 final。必须先成功调用这些工具："
                            + String.join(", ", missingTools)
                            + "。请继续执行缺失工具；不得声称尚未执行的操作已经完成。"));
                    continue;
                }
                List<String> missingSections = contract.missingSections(finalDecision.answer());
                if (!missingSections.isEmpty()) {
                    emit(RunEvents.OUTPUT_CONTRACT_BLOCKED, traceId, sessionId, step, mapOf(
                        "missing_sections", missingSections,
                        "proposed_final", Strings.truncate(finalDecision.answer(), 4_000)));
                    working.add(ChatMessage.system(
                        "最终回答结构尚未满足本轮报告契约，不能返回 final。"
                            + "请保留已有事实并补充这些章节：" + String.join(", ", missingSections)
                            + "。不得为填充章节而编造数据；缺少证据时明确写出局限。"));
                    continue;
                }
                Redaction redaction = Guardrails.redactPii(finalDecision.answer());
                if (!redaction.counts().isEmpty()) {
                    emit(RunEvents.OUTPUT_REDACTED, traceId, sessionId, step,
                        Map.of("redactions", redaction.counts()));
                }
                List<Object> evidence = new ArrayList<>(state.fullPayloads);
                for (ChatMessage message : working) {
                    if (message.role() == ChatRole.ASSISTANT && message.toolCallId() != null) {
                        evidence.add(message.content());
                    }
                }
                List<Double> unsupportedNumbers = Guardrails.unsupportedNumericClaims(
                    redaction.text(), evidence);
                if (!unsupportedNumbers.isEmpty()) {
                    emit(RunEvents.GROUNDING_BLOCKED, traceId, sessionId, step, mapOf(
                        "unsupported_numbers", unsupportedNumbers,
                        "proposed_final", Strings.truncate(redaction.text(), 4_000)));
                    List<String> rendered = unsupportedNumbers.stream().limit(12)
                        .map(AgentRuntime::gFormat).toList();
                    working.add(ChatMessage.system(
                        "最终回答包含无法在已执行工具参数或结果中找到依据的数字："
                            + String.join("、", rendered)
                            + "。请删除无证据数字，或继续调用合适工具取得证据后再回答。"
                            + "用户输入本身不能作为数据事实证据。"));
                    continue;
                }
                String answer = decorate(redaction.text(), state);
                return finish(conversation, answer, step, traceId, sessionId, activeSkills,
                    AgentStatuses.COMPLETED);
            }

            ToolCallDecision call = (ToolCallDecision) decision;
            String callId = uuidHex().substring(0, 12);
            Map<String, Object> toolSchema;
            try {
                toolSchema = tools.get(call.tool()).parameters();
            } catch (ToolException e) {
                appendToolError(conversation, working, traceId, sessionId, step,
                    call.tool(), call.arguments(), callId, e.getMessage());
                continue;
            }
            List<String> violations = contract.toolViolations(call.tool(), call.arguments(),
                toolSchema);
            if (!violations.isEmpty()) {
                emit(RunEvents.ARGUMENT_BLOCKED, traceId, sessionId, step, mapOf(
                    "tool", call.tool(), "arguments", call.arguments(),
                    "violations", violations));
                working.add(ChatMessage.system(
                    "工具 `" + call.tool() + "` 的参数违反用户原话编译出的动态契约："
                        + String.join("；", violations)
                        + "。该工具尚未执行。请修正参数后重新调用。"));
                continue;
            }
            // 策略门禁永远执行(fail-closed):无审批仓时高写直接拒绝,而非静默放行
            PolicyVerdict verdict;
            try {
                verdict = PolicyGateway.enforce(tools.get(call.tool()).policy(), call.tool());
            } catch (PolicyException | ToolException e) {
                appendToolError(conversation, working, traceId, sessionId, step,
                    call.tool(), call.arguments(), callId, messageOf(e));
                continue;
            }
            if (verdict instanceof ApprovalRequired) {
                if (approvals == null) {
                    throw new PolicyException("high-write tool requires a configured approval "
                        + "store: " + call.tool());
                }
                return pauseForApproval(conversation, traceId, sessionId, userInput, step,
                    call.tool(), call.arguments(), callId, activeSkills, allowedTools, state);
            }
            ToolExecution execution = executeTool(conversation, working, traceId, sessionId,
                step, call.tool(), call.arguments(), callId, allowedTools, state);
            if (execution.succeeded()) {
                state.completedTools.add(call.tool());
            }
        }
        String answer = "已达到最大执行步数（" + spec.maxSteps() + "），任务被 Harness 安全停止。";
        emit(RunEvents.MAX_STEPS, traceId, sessionId, spec.maxSteps(),
            java.util.Collections.emptyMap());
        return finish(conversation, decorate(answer, state), spec.maxSteps(), traceId, sessionId,
            activeSkills, AgentStatuses.FAILED);
    }

    // ---- 审批暂停 ----

    private AgentResponse pauseForApproval(Conversation conversation, String traceId,
                                           String sessionId, String userInput, int step,
                                           String toolName, Map<String, Object> arguments,
                                           String callId, List<SkillActivation> activeSkills,
                                           List<String> allowedTools, RunState state) {
        ToolPolicy policy = tools.get(toolName).policy();
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("schema_version", 2);
        checkpoint.put("full_tool_payloads", List.copyOf(state.fullPayloads));
        checkpoint.put("session_id", sessionId);
        checkpoint.put("trace_id", traceId);
        checkpoint.put("user_input", userInput);
        checkpoint.put("step", step);
        checkpoint.put("call_id", callId);
        Map<String, String> skillVersions = new LinkedHashMap<>();
        for (SkillActivation activation : activeSkills) {
            skillVersions.put(activation.name(), activation.version());
        }
        checkpoint.put("skill_versions", skillVersions);
        checkpoint.put("allowed_tools", List.copyOf(allowedTools));
        checkpoint.put("execution_summaries", List.copyOf(state.executionSummaries));
        checkpoint.put("artifact_paths", List.copyOf(state.artifactPaths));
        checkpoint.put("completed_tools", List.copyOf(state.completedTools));
        ApprovalRecord approval = approvals.request(sessionId, traceId, step, toolName,
            arguments, policy, checkpoint);
        conversations.save(conversation);
        emit(RunEvents.APPROVAL_PENDING, traceId, sessionId, step, mapOf(
            "approval_id", approval.id(), "tool", toolName,
            "arguments", arguments, "risk_level", policy.riskLevel().wire(),
            "reason", policy.reason()));
        String answer = "操作 `" + toolName + "` 需要人工审批，Agent 已保存 Checkpoint 并暂停。"
            + "审批通过后会从当前步骤继续，不会重复前面的查询。";
        return new AgentResponse(answer, step, traceId,
            activeSkills.stream().map(SkillActivation::name).toList(),
            AgentStatuses.APPROVAL_PENDING, approval.asMap());
    }

    // ---- 工具执行 ----

    private ToolExecution executeTool(Conversation conversation, List<ChatMessage> working,
                                      String traceId, String sessionId, int step,
                                      String toolName, Map<String, Object> arguments,
                                      String callId, List<String> allowedTools, RunState state) {
        emit(RunEvents.TOOL_START, traceId, sessionId, step,
            mapOf("tool", toolName, "arguments", arguments));
        long toolStarted = System.nanoTime();
        String payload;
        boolean succeeded;
        String error = null;
        try {
            Object result = tools.execute(toolName, arguments, allowedTools);
            payload = Json.write(result);
            String summary = tools.formatResult(toolName, result);
            if (summary != null && !summary.isEmpty()) {
                state.executionSummaries.add(summary);
            }
            if (result instanceof Map<?, ?> resultMap && resultMap.get("storage_path") != null) {
                state.artifactPaths.add(String.valueOf(resultMap.get("storage_path")));
            }
            // 高写工具的执行证明是审计记录:落库失败按失败记账(fail-closed)
            emit(RunEvents.TOOL_END, traceId, sessionId, step, mapOf(
                "tool", toolName, "result", result,
                "latency_ms", round2((System.nanoTime() - toolStarted) / 1e6),
                "risk_level", tools.get(toolName).policy().riskLevel().wire()),
                tools.get(toolName).policy().riskLevel() == RiskLevel.HIGH_WRITE);
            if (result instanceof Map<?, ?> degradedMap
                && Boolean.TRUE.equals(degradedMap.get("degraded"))
                && degradedMap.containsKey("error")
                && tools.get(toolName).policy().riskLevel() == RiskLevel.HIGH_WRITE) {
                // 降级的高写工具 = 实际未执行(熔断/重连中没有任何业务动作发生)。
                // 按失败记账:审批恢复路径 markExecution(false) 让审批卡如实显示
                // "执行失败"而非"已执行";completed_tools 不追加;降级载荷仍进上下文。
                error = String.valueOf(degradedMap.get("error"));
                emit(RunEvents.TOOL_ERROR, traceId, sessionId, step,
                    mapOf("tool", toolName, "error", error));
                succeeded = false;
            } else {
                succeeded = true;
                // 门禁用完整值,截断只影响模型可见面
                state.fullPayloads.add(payload);
            }
        } catch (ToolException e) {
            payload = Json.write(Map.of("error", String.valueOf(e.getMessage())));
            error = e.getMessage();
            emit(RunEvents.TOOL_ERROR, traceId, sessionId, step, mapOf(
                "tool", toolName, "error", error,
                "latency_ms", round2((System.nanoTime() - toolStarted) / 1e6)));
            succeeded = false;
        }
        appendToolMessages(conversation, working, toolName, arguments, callId,
            contextualPayload(payload, traceId, step));
        return new ToolExecution(payload, succeeded, error);
    }

    private void appendToolError(Conversation conversation, List<ChatMessage> working,
                                 String traceId, String sessionId, int step, String toolName,
                                 Map<String, Object> arguments, String callId, String error) {
        String payload = Json.write(Map.of("error", error));
        emit(RunEvents.TOOL_ERROR, traceId, sessionId, step,
            mapOf("tool", toolName, "error", error));
        appendToolMessages(conversation, working, toolName, arguments, callId, payload);
    }

    private static void appendToolMessages(Conversation conversation,
                                           List<ChatMessage> working, String toolName,
                                           Map<String, Object> arguments, String callId,
                                           String payload) {
        ChatMessage assistantCall = new ChatMessage(ChatRole.ASSISTANT,
            Json.write(Map.of("tool_call", Map.of("name", toolName, "arguments", arguments))),
            null, callId);
        ChatMessage toolResult = ChatMessage.tool(toolName, payload, callId);
        working.add(assistantCall);
        working.add(toolResult);
        conversation.addMessage(assistantCall);
        conversation.addMessage(toolResult);
    }

    /** 模型可见面截断;完整结果已在 tool_end Trace 与门禁证据中。 */
    private String contextualPayload(String payload, String traceId, int step) {
        int limit = spec.toolResultContextLimit();
        if (limit <= 0 || Strings.len(payload) <= limit) {
            return payload;
        }
        return Strings.truncate(payload, limit) + "\n…[工具结果过长,已截断至 " + limit
            + " 字符;完整结果存证于 Trace 第 " + step + " 步(" + traceId.substring(0, 8)
            + "),可依据以上摘要作答]";
    }

    // ---- 技能激活 ----

    private record Activation(List<SkillActivation> skills, List<String> allowedTools) {}

    private Activation activate(String userInput, String traceId, String sessionId,
                                boolean emitEvents) {
        List<SkillActivation> activeSkills = List.of();
        List<String> allowedTools = spec.toolNames();
        if (skills != null) {
            List<SkillActivation> activated = skills.activate(userInput);
            allowedTools = SkillRuntime.allowedTools(activated, spec.toolNames());
            Set<String> available = new LinkedHashSet<>(allowedTools);
            List<SkillActivation> resolvedSkills = new ArrayList<>();
            for (SkillActivation activation : activated) {
                List<List<String>> plan = !activation.requiredToolPlan().isEmpty()
                    ? activation.requiredToolPlan()
                    : planFrom(activation.requiredTools(), activation.requiredToolGroups());
                List<String> resolvedPlan = new ArrayList<>();
                for (List<String> alternatives : plan) {
                    String selected = alternatives.stream()
                        .filter(available::contains).findFirst().orElse(null);
                    if (selected == null) {
                        // 组内工具对当前 Agent 全部不可用:跳过该组,允许降级完成
                        continue;
                    }
                    resolvedPlan.add(selected);
                }
                List<String> resolvedTools = distinct(resolvedPlan);
                resolvedSkills.add(new SkillActivation(activation.name(),
                    activation.description(), activation.instructions(), activation.version(),
                    activation.reason(), activation.score(), activation.allowedTools(),
                    resolvedTools, activation.requiredToolGroups(),
                    planFrom(resolvedTools, List.of()), activation.outputContract()));
            }
            activeSkills = resolvedSkills;
            Set<String> requiredTools = new LinkedHashSet<>();
            for (SkillActivation activation : activeSkills) {
                requiredTools.addAll(activation.requiredTools());
            }
            Set<String> unavailable = new TreeSet<>(requiredTools);
            unavailable.removeAll(allowedTools);
            if (!unavailable.isEmpty()) {
                throw new SkillException(
                    "request requires unavailable tools: " + String.join(", ", unavailable));
            }
            if (emitEvents) {
                for (SkillActivation activation : activeSkills) {
                    emit(RunEvents.SKILL_ACTIVATED, traceId, sessionId, 0, mapOf(
                        "skill", activation.name(), "version", activation.version(),
                        "reason", activation.reason(), "score", activation.score(),
                        "allowed_tools", activation.allowedTools(),
                        "required_tools", activation.requiredTools()));
                }
            }
        }
        return new Activation(activeSkills, allowedTools);
    }

    private static List<List<String>> planFrom(List<String> requiredTools,
                                               List<List<String>> requiredGroups) {
        List<List<String>> plan = new ArrayList<>();
        for (String tool : requiredTools) {
            plan.add(List.of(tool));
        }
        plan.addAll(requiredGroups);
        return plan;
    }

    /** 完成契约缺口的有序游标匹配:completed 序列需按顺序覆盖 required 序列。 */
    static List<String> missingRequiredTools(List<SkillActivation> activeSkills,
                                             List<String> completedTools) {
        List<String> missing = new ArrayList<>();
        for (SkillActivation activation : activeSkills) {
            int cursor = 0;
            List<String> required = activation.requiredTools();
            for (String completed : completedTools) {
                if (cursor < required.size() && completed.equals(required.get(cursor))) {
                    cursor++;
                }
            }
            missing.addAll(required.subList(cursor, required.size()));
        }
        return distinct(missing);
    }

    // ---- 历史压缩 ----

    /** 确定性压缩:较早轮次折叠为"问/答"摘要,工具消息丢弃;返回新对象,入参不变。 */
    public static Conversation compressHistory(Conversation conversation, int keepRecent) {
        return compressHistory(conversation, keepRecent, 20);
    }

    public static Conversation compressHistory(Conversation conversation, int keepRecent,
                                               int maxPairs) {
        List<ChatMessage> messages = conversation.messages();
        if (messages.size() <= keepRecent) {
            return conversation;
        }
        List<ChatMessage> older = messages.subList(0, messages.size() - keepRecent);
        List<ChatMessage> recent = messages.subList(messages.size() - keepRecent,
            messages.size());
        List<String> pairs = new ArrayList<>();
        String pendingQuestion = null;
        for (ChatMessage message : older) {
            if (message.role() == ChatRole.USER && message.toolCallId() == null) {
                if (pendingQuestion != null) {
                    pairs.add("- 问:" + Strings.truncate(pendingQuestion, 120)
                        + " 答:(该轮无最终回答)");
                }
                pendingQuestion = message.content().strip();
            } else if (message.role() == ChatRole.ASSISTANT
                && message.toolCallId() == null && pendingQuestion != null) {
                String answer = message.content().strip();
                if (answer.startsWith("{")) {
                    try {
                        answer = Json.readTree(answer).path("final").asText(answer);
                    } catch (RuntimeException ignored) {
                        // 保持原文
                    }
                }
                pairs.add("- 问:" + Strings.truncate(pendingQuestion, 120)
                    + " 答:" + Strings.truncate(answer, 200));
                pendingQuestion = null;
            }
        }
        if (pendingQuestion != null) {
            pairs.add("- 问:" + Strings.truncate(pendingQuestion, 120)
                + " 答:(该轮无最终回答)");
        }
        List<String> segments = new ArrayList<>(conversation.getSummary().lines()
            .filter(line -> line.startsWith("- 问:")).toList());
        segments.addAll(pairs);
        List<String> kept = segments.size() > maxPairs
            ? segments.subList(segments.size() - maxPairs, segments.size()) : segments;
        return conversation.withSummaryAndMessages(String.join("\n", kept), recent);
    }

    // ---- 收尾与事件 ----

    private static String decorate(String answer, RunState state) {
        List<String> parts = new ArrayList<>();
        parts.add(answer.strip());
        if (!state.executionSummaries.isEmpty()) {
            parts.add("本轮实际执行结果：\n"
                + String.join("\n", state.executionSummaries.stream()
                    .map(summary -> "- " + summary).toList()));
        }
        List<String> uniquePaths = distinct(state.artifactPaths);
        if (!uniquePaths.isEmpty()) {
            parts.add("数据文件：\n"
                + String.join("\n", uniquePaths.stream().map(path -> "- " + path).toList()));
        }
        return String.join("\n\n", parts);
    }

    private AgentResponse finish(Conversation conversation, String answer, int steps,
                                 String traceId, String sessionId,
                                 List<SkillActivation> activeSkills, String status) {
        Redaction redaction = Guardrails.redactPii(answer);
        if (!redaction.counts().isEmpty()) {
            emit(RunEvents.OUTPUT_REDACTED, traceId, sessionId, steps,
                Map.of("redactions", redaction.counts()));
        }
        conversation.addMessage(ChatMessage.assistant(redaction.text()));
        conversations.save(conversation);
        emit(RunEvents.RUN_END, traceId, sessionId, steps,
            mapOf("answer", redaction.text(), "status", status));
        return new AgentResponse(redaction.text(), steps, traceId,
            activeSkills.stream().map(SkillActivation::name).toList(), status, null);
    }

    /**
     * 审计关键事件:落库失败必须让操作失败(fail-closed)。对一个「可审计 Agent」,
     * 审批暂停/拒绝与高写工具的执行证明丢失是不可接受的 —— 宁可不执行。
     */
    private static final java.util.Set<String> AUDIT_EVENTS = java.util.Set.of(
        RunEvents.APPROVAL_PENDING, RunEvents.APPROVAL_REJECTED);

    private void emit(String eventType, String traceId, String sessionId, int step,
                      Map<String, Object> data) {
        emit(eventType, traceId, sessionId, step, data, AUDIT_EVENTS.contains(eventType));
    }

    private void emit(String eventType, String traceId, String sessionId, int step,
                      Map<String, Object> data, boolean auditCritical) {
        RunEvent event = RunEvent.of(eventType, traceId, sessionId, step, data);
        for (Consumer<RunEvent> hook : hooks) {
            try {
                hook.accept(event);
            } catch (Exception e) {
                // 遥测 hook 失败不破坏主循环(hook 是可降级观测)。
            }
        }
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("timestamp", event.timestamp());
            record.put("event", event.eventType());
            record.put("trace_id", event.traceId());
            record.put("step", event.step());
            record.put("agent", spec.name());
            record.putAll(event.data());
            traceWriter.appendEvent(sessionId, traceId, spec.name(), record);
        } catch (Exception e) {
            if (auditCritical) {
                throw new IllegalStateException(
                    "audit-trail write failed for " + eventType + "; failing closed", e);
            }
            // 遥测事件(模型/技能/压缩等)落库失败降级:不破坏主循环。
        }
    }

    // ---- 小工具 ----

    private static final class RunState {
        final List<String> executionSummaries = new ArrayList<>();
        final List<String> artifactPaths = new ArrayList<>();
        final List<String> completedTools = new ArrayList<>();
        final List<String> fullPayloads = new ArrayList<>();

        static RunState fromCheckpoint(Map<String, Object> checkpoint) {
            RunState state = new RunState();
            state.executionSummaries.addAll(stringList(
                checkpoint.get("execution_summaries")));
            state.artifactPaths.addAll(stringList(checkpoint.get("artifact_paths")));
            state.completedTools.addAll(stringList(checkpoint.get("completed_tools")));
            state.fullPayloads.addAll(stringList(checkpoint.get("full_tool_payloads")));
            return state;
        }
    }

    private record ToolExecution(String payload, boolean succeeded, String error) {}

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : String.valueOf(e);
    }

    /** 允许 null 值的事件数据 map(Map.of 不接受 null)。 */
    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** %g:整数无小数点,小数去尾零。 */
    static String gFormat(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)
            && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
