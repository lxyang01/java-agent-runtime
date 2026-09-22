package io.github.lxyang01.agent.engine;

/** 模型输出不含合法的 tool_call/final(对齐 Python DecisionParseError(ValueError))。 */
public class DecisionParseException extends IllegalArgumentException {

    public DecisionParseException(String message) {
        super(message);
    }
}
