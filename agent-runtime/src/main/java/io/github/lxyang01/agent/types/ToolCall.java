package io.github.lxyang01.agent.types;

import java.util.Map;

/** 模型决策中的一次工具调用(name + 已解析的参数对象)。 */
public record ToolCall(String name, Map<String, Object> arguments) {
}
