package io.github.lxyang01.billguard.mcp;

/**
 * 熔断拒绝/重连进行中:服务暂不可用,调用被快速失败(不发起网络调用)。
 * 注册表 handler 捕获本类型并转为 {"error":..., "degraded":true} 结构化结果,
 * Agent 循环存活;其余 McpException 保持原语义(ToolError → tool_error 事件)。
 */
public class McpCircuitOpenException extends McpException {

    public McpCircuitOpenException(String message) {
        super(message);
    }
}
