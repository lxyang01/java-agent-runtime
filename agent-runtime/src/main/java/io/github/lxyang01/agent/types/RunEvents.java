package io.github.lxyang01.agent.types;

/**
 * 事件名常量(契约保形:与 Python 版 engine.py 的 _emit 调用逐字一致,
 * traces 表 events JSONB 与 /api/runs 投影都依赖这些字符串)。
 */
public final class RunEvents {

    public static final String RUN_START = "run_start";
    public static final String RUN_RESUME = "run_resume";
    public static final String RUN_END = "run_end";
    public static final String RUN_ERROR = "run_error";
    public static final String RUN_TIMEOUT = "run_timeout";
    public static final String MAX_STEPS = "max_steps";
    public static final String MODEL_START = "model_start";
    public static final String MODEL_OUTPUT = "model_output";
    public static final String MODEL_DECISION = "model_decision";
    public static final String MODEL_OUTPUT_BLOCKED = "model_output_blocked";
    public static final String TOOL_START = "tool_start";
    public static final String TOOL_END = "tool_end";
    public static final String TOOL_ERROR = "tool_error";
    public static final String ARGUMENT_BLOCKED = "argument_blocked";
    public static final String COMPLETION_BLOCKED = "completion_blocked";
    public static final String OUTPUT_CONTRACT_BLOCKED = "output_contract_blocked";
    public static final String OUTPUT_REDACTED = "output_redacted";
    public static final String GROUNDING_BLOCKED = "grounding_blocked";
    public static final String APPROVAL_PENDING = "approval_pending";
    public static final String APPROVAL_REJECTED = "approval_rejected";
    public static final String SKILL_ACTIVATED = "skill_activated";
    public static final String SKILL_ERROR = "skill_error";
    public static final String HISTORY_COMPRESSED = "history_compressed";

    private RunEvents() {}
}
