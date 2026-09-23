package io.github.lxyang01.agent.tool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 注册表:登记/查询/按白名单输出 schema/校验参数并执行。注册表语义。 */
public final class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public void register(ToolDefinition tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("tool already registered: " + tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public ToolDefinition get(String name) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            throw new ToolException("unknown tool: " + name);
        }
        return tool;
    }

    /** 模型可见的 schema 列表(按 allowed 顺序)。 */
    public List<Map<String, Object>> schemas(Collection<String> allowed) {
        Collection<String> selected = allowed != null ? allowed : tools.keySet();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : selected) {
            result.add(get(name).schema());
        }
        return result;
    }

    public Object execute(String name, Map<String, Object> arguments, Collection<String> allowed) {
        if (allowed != null && !allowed.contains(name)) {
            throw new ToolException("tool is not enabled for this agent: " + name);
        }
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            throw new ToolException("unknown tool: " + name);
        }
        validate(tool.parameters(), arguments);
        try {
            return tool.handler().execute(arguments);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException(name + " failed: " + e.getMessage(), e);
        }
    }

    public String formatResult(String name, Object result) {
        ToolDefinition tool = tools.get(name);
        if (tool == null || tool.resultFormatter() == null) {
            return null;
        }
        return tool.resultFormatter().apply(result);
    }

    /** JSON Schema 子集校验(required/additionalProperties/type/enum/minimum)。 */
    static void validate(Map<String, Object> schema, Map<String, Object> arguments) {
        if (arguments == null) {
            throw new ToolException("tool arguments must be an object");
        }
        Map<String, Object> properties = mapValue(schema.get("properties"));
        List<?> required = schema.get("required") instanceof List<?> list ? list : List.of();

        for (Object key : required) {
            if (!arguments.containsKey(key)) {
                throw new ToolException("missing required argument: " + key);
            }
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            TreeSet<String> extra = new TreeSet<>(arguments.keySet());
            extra.removeAll(properties.keySet());
            if (!extra.isEmpty()) {
                throw new ToolException("unexpected argument(s): " + String.join(", ", extra));
            }
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            validateProperty(entry.getKey(), entry.getValue(),
                mapValue(properties.get(entry.getKey())));
        }
    }

    private static void validateProperty(String key, Object value, Map<String, Object> rule) {
        Object expected = rule.get("type");
        if (expected instanceof String type && !matchesType(type, value)) {
            throw new ToolException("argument " + key + " must be " + type);
        }
        if (rule.get("enum") instanceof List<?> allowedValues && !allowedValues.contains(value)) {
            throw new ToolException("argument " + key + " must be one of " + stringList(allowedValues));
        }
        if (rule.get("minimum") instanceof Number minimum && value instanceof Number number
            && number.doubleValue() < minimum.doubleValue()) {
            throw new ToolException("argument " + key + " must be >= " + minimum);
        }
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            // 排除 bool 冒充 int/float;Java 中 Boolean 本就不是 Number,天然排除
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }

    /** 列表 repr 形式(字符串带单引号),错误消息契约。 */
    private static String stringList(List<?> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object value = values.get(i);
            sb.append(value instanceof String s ? "'" + s + "'" : String.valueOf(value));
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
