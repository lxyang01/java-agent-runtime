package io.github.lxyang01.agent.tool;

/** 工具执行/校验错误(引擎转为 {"error": msg} 工具结果,循环继续)。 */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
