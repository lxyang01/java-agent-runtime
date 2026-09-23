package io.github.lxyang01.billguard.mcp;

import java.util.List;

/** 连接快照:server 信息 + 工具/资源/prompt 目录(MCPServerSnapshot)。 */
public record McpServerSnapshot(String name, String transport, String serverName,
                                String serverVersion, String protocolVersion,
                                List<McpToolInfo> tools, List<String> resources,
                                List<String> prompts) {

    /** /api/snapshot 的 mcp_servers 视图形状。 */
    public java.util.Map<String, Object> view() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", name);
        map.put("transport", transport);
        map.put("server_name", serverName);
        map.put("server_version", serverVersion);
        map.put("protocol_version", protocolVersion);
        map.put("tools", tools.stream().map(McpToolInfo::name).toList());
        map.put("resources", resources);
        map.put("prompts", prompts);
        return map;
    }
}
