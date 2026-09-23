package io.github.lxyang01.billguard.mcp;

import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * MCP 模式的 owner 身份注入(对齐 web.py inject_owner_identity):
 * bill.* 工具 handler 包装 —— 参数按 schema 白名单过滤、owner(与已声明的 operator)
 * 强制覆盖为服务端身份;schema 的 properties/required 移除身份键(模型不可见)。
 * 非 bill.* 工具原样透传。返回新注册表,入参不变。
 */
public final class OwnerIdentity {

    private OwnerIdentity() {}

    public static ToolRegistry injectOwnerIdentity(ToolRegistry registry, String username) {
        ToolRegistry injected = new ToolRegistry();
        for (String name : registry.names()) {
            ToolDefinition tool = registry.get(name);
            if (!name.startsWith("bill.")) {
                injected.register(tool);
                continue;
            }
            Map<String, Object> parameters = deepCopy(tool.parameters());
            Map<String, Object> properties =
                parameters.get("properties") instanceof Map<?, ?> raw
                    ? asObjectMap(raw) : new LinkedHashMap<>();
            Set<String> identities = new LinkedHashSet<>();
            identities.add("owner");
            if (properties.containsKey("operator")) {
                identities.add("operator");
            }
            properties.keySet().removeAll(identities);
            if (parameters.get("required") instanceof java.util.List<?> required) {
                parameters.put("required", required.stream()
                    .filter(key -> !identities.contains(String.valueOf(key))).toList());
            }
            ToolDefinition sanitized = new ToolDefinition(tool.name(), tool.description(),
                parameters, tool.policy(),
                arguments -> {
                    // 按白名单过滤 + 身份键强制覆盖:模型伪造的 owner/operator 无效
                    Map<String, Object> safe = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                        if (properties.containsKey(entry.getKey())) {
                            safe.put(entry.getKey(), entry.getValue());
                        }
                    }
                    for (String identity : identities) {
                        safe.put(identity, username);
                    }
                    return tool.handler().execute(safe);
                }, tool.resultFormatter());
            injected.register(sanitized);
        }
        return injected;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() instanceof Map<?, ?> nested
                ? deepCopy(asObjectMap(nested)) : entry.getValue());
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
