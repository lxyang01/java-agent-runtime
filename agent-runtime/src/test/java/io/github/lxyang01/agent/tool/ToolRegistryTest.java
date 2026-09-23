package io.github.lxyang01.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.policy.ToolPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static final ToolHandler EMPTY = args -> Map.of();

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required,
            "additionalProperties", false);
    }

    @Test
    void duplicate_registration_rejected() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "d", schema(Map.of(), List.of()), EMPTY));
        assertThatThrownBy(() ->
            registry.register(ToolDefinition.of("t", "d", schema(Map.of(), List.of()), EMPTY)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("tool already registered: t");
    }

    @Test
    void unknown_tool_throws() {
        assertThatThrownBy(() -> new ToolRegistry().get("nope"))
            .isInstanceOf(ToolException.class)
            .hasMessage("unknown tool: nope");
    }

    @Test
    void execute_blocked_when_not_in_allowed_list() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "d", schema(Map.of(), List.of()), EMPTY));
        assertThatThrownBy(() -> registry.execute("t", Map.of(), List.of("other")))
            .isInstanceOf(ToolException.class)
            .hasMessage("tool is not enabled for this agent: t");
    }

    @Test
    void missing_required_argument_rejected() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "d",
            schema(Map.of("q", Map.of("type", "string")), List.of("q")), EMPTY));
        assertThatThrownBy(() -> registry.execute("t", Map.of(), null))
            .isInstanceOf(ToolException.class)
            .hasMessage("missing required argument: q");
    }

    @Test
    void unexpected_argument_rejected_when_additional_properties_false() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "d",
            schema(Map.of("q", Map.of("type", "string")), List.of()), EMPTY));
        assertThatThrownBy(() -> registry.execute("t", Map.of("q", "x", "extra", 1), null))
            .isInstanceOf(ToolException.class)
            .hasMessage("unexpected argument(s): extra");
    }

    @Test
    void type_validation_matrix() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "d", Map.of(
            "type", "object",
            "properties", Map.of(
                "s", Map.of("type", "string"),
                "n", Map.of("type", "number"),
                "i", Map.of("type", "integer"),
                "b", Map.of("type", "boolean"),
                "arr", Map.of("type", "array")),
            "required", List.of(),
            "additionalProperties", false), EMPTY));

        assertThatThrownBy(() -> registry.execute("t", Map.of("s", 5), null))
            .hasMessage("argument s must be string");
        assertThatThrownBy(() -> registry.execute("t", Map.of("n", true), null))
            .hasMessage("argument n must be number");       // 布尔不算数字(显式排除)
        assertThatThrownBy(() -> registry.execute("t", Map.of("n", "5"), null))
            .hasMessage("argument n must be number");
        assertThatThrownBy(() -> registry.execute("t", Map.of("i", 5.0), null))
            .hasMessage("argument i must be integer");      // 5.0 是浮点,不算 integer
        assertThatThrownBy(() -> registry.execute("t", Map.of("b", "yes"), null))
            .hasMessage("argument b must be boolean");
        assertThatThrownBy(() -> registry.execute("t", Map.of("arr", Map.of()), null))
            .hasMessage("argument arr must be array");

        // 合法组合:integer 接受整型,number 接受整型与浮点
        assertThat(registry.execute("t", Map.of("i", 5), null)).isEqualTo(Map.of());
        assertThat(registry.execute("t", Map.of("n", 5), null)).isEqualTo(Map.of());
        assertThat(registry.execute("t", Map.of("n", 5.5), null)).isEqualTo(Map.of());
    }

    @Test
    void enum_and_minimum_validated() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "d", Map.of(
            "type", "object",
            "properties", Map.of(
                "level", Map.of("type", "string", "enum", List.of("low", "high")),
                "hours", Map.of("type", "number", "minimum", 0)),
            "required", List.of(),
            "additionalProperties", false), EMPTY));

        assertThatThrownBy(() -> registry.execute("t", Map.of("level", "mid"), null))
            .hasMessage("argument level must be one of ['low', 'high']");
        assertThatThrownBy(() -> registry.execute("t", Map.of("hours", -1), null))
            .hasMessage("argument hours must be >= 0");
    }

    @Test
    void handler_failure_wrapped_as_tool_error() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "d", schema(Map.of(), List.of()), args -> {
            throw new IllegalStateException("boom");
        }));
        assertThatThrownBy(() -> registry.execute("t", Map.of(), null))
            .isInstanceOf(ToolException.class)
            .hasMessage("t failed: boom");
    }

    @Test
    void schemas_wire_shape_excludes_policy() {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("t", "说明",
            schema(Map.of("q", Map.of("type", "string")), List.of()), EMPTY));
        var schemas = registry.schemas(List.of("t"));
        assertThat(schemas).singleElement().satisfies(schema -> {
            assertThat(schema).containsOnlyKeys("name", "description", "parameters");
        });
    }

    @Test
    void result_formatter_optional() {
        var registry = new ToolRegistry();
        registry.register(new ToolDefinition("t", "d", schema(Map.of(), List.of()),
            ToolPolicy.read(), args -> Map.of("v", 1), r -> "值=" + ((Map<?, ?>) r).get("v")));
        assertThat(registry.formatResult("t", Map.of("v", 1))).isEqualTo("值=1");
        registry.register(ToolDefinition.of("t2", "d", schema(Map.of(), List.of()), EMPTY));
        assertThat(registry.formatResult("t2", Map.of())).isNull();
    }
}
