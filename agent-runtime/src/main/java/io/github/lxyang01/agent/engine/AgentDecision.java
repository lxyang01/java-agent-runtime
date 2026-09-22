package io.github.lxyang01.agent.engine;

import java.util.Map;

/**
 * 模型单步输出解析后的类型化结果。
 * 解析失败抛 {@link DecisionParseException} → 引擎记 run_error 以 failed 收尾
 * (行为契约,对齐 Python 版 —— 不做注入重试)。
 */
public sealed interface AgentDecision permits ToolCallDecision, FinalDecision {

}

/** 工具调用决策(优先级高于同时出现的 premature final)。 */
record ToolCallDecision(String thought, String tool, Map<String, Object> arguments)
    implements AgentDecision {
}

/** 最终回答决策。 */
record FinalDecision(String thought, String answer) implements AgentDecision {
}
