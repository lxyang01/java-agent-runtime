package io.github.lxyang01.billguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.tool.ToolDefinition;
import io.github.lxyang01.agent.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** owner 身份注入:schema 隐藏 + 参数白名单 + 身份强制覆盖;非 bill.* 透传。 */
class OwnerIdentityTest {

    private static ToolRegistry registryWithBillAndWorkItem(
        java.util.function.BiFunction<String, Map<String, Object>, Object> capture) {
        ToolRegistry registry = new ToolRegistry();
        Map<String, Object> billParams = new java.util.LinkedHashMap<>();
        billParams.put("type", "object");
        billParams.put("properties", new java.util.LinkedHashMap<>(Map.of(
            "merchant", Map.of("type", "string"),
            "owner", Map.of("type", "string"),
            "operator", Map.of("type", "string"))));
        billParams.put("required", List.of("merchant", "owner"));
        billParams.put("additionalProperties", false);
        registry.register(new ToolDefinition("bill.query", "查询", billParams,
            ToolPolicy.read(),
            args -> capture.apply("bill.query", args), null));
        registry.register(ToolDefinition.of("work-items.prepare_issue", "工单草稿",
            Map.of("type", "object", "properties", Map.of(
                "title", Map.of("type", "string")), "required", List.of("title")),
            args -> capture.apply("work-items.prepare_issue", args)));
        return registry;
    }

    @Test
    void bill_tools_schema_hides_identity_keys() {
        var capture = new java.util.function.BiFunction<String, Map<String, Object>, Object>() {
            @Override
            public Object apply(String s, Map<String, Object> m) {
                return Map.of();
            }
        };
        ToolRegistry injected = OwnerIdentity.injectOwnerIdentity(
            registryWithBillAndWorkItem(capture), "alice");
        Map<String, Object> schema = injected.get("bill.query").parameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).doesNotContainKeys("owner", "operator");
        assertThat((List<String>) (List<?>) schema.get("required"))
            .containsExactly("merchant");
        // 非 bill.* 原样(工单工具无身份键概念,保持)
        assertThat(injected.get("work-items.prepare_issue").parameters()
            .get("properties")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKey("title");
    }

    @Test
    void forged_owner_overwritten_and_unknown_args_dropped() throws Exception {
        Map<String, Object> received = new LinkedHashMap<>();
        var capture = new java.util.function.BiFunction<String, Map<String, Object>, Object>() {
            @Override
            public Object apply(String tool, Map<String, Object> args) {
                received.put("_tool", tool);
                received.putAll(args);
                return Map.of("ok", true);
            }
        };
        ToolRegistry injected = OwnerIdentity.injectOwnerIdentity(
            registryWithBillAndWorkItem(capture), "alice");

        // 模型伪造 owner/operator + 未知参数 _server。
        // 直调 handler(注册表 schema 校验层已把伪造键挡在外面 —— additionalProperties=false;
        // wrapper 是纵深防御第二层,负责 MCP 路径的身份强制覆盖)
        injected.get("bill.query").handler().execute(new java.util.LinkedHashMap<>(Map.of(
            "merchant", "美团", "owner", "bob", "operator", "mallory", "_server", "evil")));

        assertThat(received.get("_tool")).isEqualTo("bill.query");
        assertThat(received.get("owner")).isEqualTo("alice");       // 强制覆盖
        assertThat(received.get("operator")).isEqualTo("alice");    // 已声明的 operator 同样注入
        assertThat(received.get("merchant")).isEqualTo("美团");
        assertThat(received).doesNotContainKey("_server");          // 白名单外丢弃
    }
}
