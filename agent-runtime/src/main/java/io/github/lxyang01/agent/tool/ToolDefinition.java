package io.github.lxyang01.agent.tool;

import io.github.lxyang01.agent.policy.ToolPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具定义。schema() 是模型可见面契约:{name, description, parameters} —— 不含 policy。
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> parameters,
    ToolPolicy policy,
    ToolHandler handler,
    Function<Object, String> resultFormatter) {

    public ToolDefinition {
        parameters = Map.copyOf(parameters);
    }

    public static ToolDefinition of(String name, String description,
                                    Map<String, Object> parameters, ToolHandler handler) {
        return new ToolDefinition(name, description, parameters, ToolPolicy.read(), handler, null);
    }

    public Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name);
        schema.put("description", description);
        schema.put("parameters", parameters);
        return schema;
    }
}
