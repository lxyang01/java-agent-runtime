package io.github.lxyang01.billguard.mcp;

import io.github.lxyang01.agent.policy.ToolPolicy;
import java.util.Map;

/** 远端工具信息(schema + 策略推导)。 */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema,
                          ToolPolicy policy) {
}
