package io.github.lxyang01.agent.tool;

import java.util.Map;

/**
 * 工具执行入口。owner 等多租户上下文在装配期闭包绑定(与 Python 版一致),
 * 引擎不感知租户;返回值 Object 直通序列化 —— 引擎对工具结果零假设。
 */
@FunctionalInterface
public interface ToolHandler {

    Object execute(Map<String, Object> arguments) throws Exception;
}
