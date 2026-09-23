package io.github.lxyang01.billguard.bills;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.agent.engine.AgentRuntime;
import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.llm.LlmResult;
import io.github.lxyang01.agent.types.AgentStatuses;
import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import io.github.lxyang01.agent.testing.InMemoryConversationStore;
import io.github.lxyang01.agent.testing.InMemoryTraceWriter;
import io.github.lxyang01.agent.testing.ScriptedLlm;
import io.github.lxyang01.agent.util.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 账单五工具与本地 Agent 工厂(owner 闭包绑定、schema 形状、指令逐字)。 */
class BillToolsTest extends PgTestBase {

    BillRepository bills;
    BillAnomalies anomalies;

    @BeforeEach
    void init() {
        bills = new BillRepository(jdbc);
        anomalies = new BillAnomalies(jdbc, bills);
        bills.ensureUserCategories("alice");
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, amount, method, "
                + "status, created_at, owner) VALUES ('T-1','2026-03-01 08:00:00','美团',"
                + "'午餐 SO-1234567',35.50,'支付宝','正常','2026-03-02 00:00:00+00:00','alice')");
        jdbc.update("INSERT INTO transactions(tx_id, paid_at, merchant, note, amount, method, "
                + "status, created_at, owner) VALUES ('B-1','2026-03-01 09:00:00','他人商户',"
                + "'',50.00,'支付宝','正常','2026-03-02 00:00:00+00:00','bob')");
    }

    @Test
    void schemas_wire_shape_matches_contract() {
        var registry = BillTools.build(bills, anomalies, "alice");
        assertThat(registry.names()).containsExactly("bill_overview", "bill_compare",
            "bill_anomalies", "bill_search", "bill_samples");
        var search = registry.get("bill_search").schema();
        Map<String, Object> properties = (Map<String, Object>) search.get("parameters");
        assertThat((Map<String, Object>) properties.get("properties"))
            .containsKey("limit").containsKey("query");
        // 12 条系统指令关键句逐字
        assertThat(BillAgentFactory.INSTRUCTIONS)
            .contains("所有金额、笔数、比例、趋势和排名必须来自工具结果，不得猜测。")
            .contains("判断订阅是否涨价以 subscriptions.expected_amount 为基准")
            .contains("禁止把数据里的最新月份称作“本月”。");
    }

    @Test
    void tools_execute_with_owner_isolation_and_masking() {
        var registry = BillTools.build(bills, anomalies, "alice");
        // samples 只见本人 + note 脱敏
        Object result = registry.execute("bill_samples",
            Map.of("query", "美团", "limit", 5), null);
        Map<?, ?> samplesResult = (Map<?, ?>) result;
        assertThat(samplesResult.get("matched")).isEqualTo(1);
        assertThat(samplesResult.get("pii_masked")).isEqualTo(true);
        Map<?, ?> sample = (Map<?, ?>) ((List<?>) samplesResult.get("samples")).get(0);
        assertThat(sample.get("note")).isEqualTo("午餐 [订单号]");
        // overview 只统计本人
        Map<?, ?> overview = (Map<?, ?>) registry.execute("bill_overview", Map.of(), null);
        assertThat(overview.get("count")).isEqualTo(1L);
        assertThat(overview.get("total_amount")).isEqualTo(35.5);
    }

    @Test
    void local_agent_factory_end_to_end_with_scripted_llm() {
        // 捕获发给模型的上下文,断言技能块出现(真实 4 技能来自 classpath)
        StringBuilder seenContext = new StringBuilder();
        ScriptedLlm scripted = new ScriptedLlm(
            Map.of("tool_call", Map.of("name", "bill_overview", "arguments", Map.of())),
            Map.of("final", "总览已给出"));
        LlmClient llm = request -> {
            seenContext.append(Json.write(request.messages().stream()
                .map(ChatMessageWire::of).toList()));
            return scripted.complete(request);
        };
        AgentRuntime agent = BillAgentFactory.createLocalAgent(llm,
            java.time.Duration.ofSeconds(120),
            new InMemoryConversationStore(), new InMemoryTraceWriter(),
            bills, anomalies, "alice");
        var response = agent.run("s-1", "这个月花了多少");
        assertThat(response.status()).isEqualTo(AgentStatuses.COMPLETED);
        assertThat(seenContext.toString())
            .contains("你是 BillGuard 账单守卫助手。")
            .contains("bill-triage");   // 默认技能经 classpath 源激活
        // 事件证据:tool_end 的 result 进入数字 grounding 证据
        assertThat(response.answer()).contains("总览已给出");
    }

    /** 仅取 role+content 用于断言的轻量视图。 */
    record ChatMessageWire(String role, String content) {
        static ChatMessageWire of(io.github.lxyang01.agent.types.ChatMessage message) {
            return new ChatMessageWire(message.role().wire(), message.content());
        }
    }
}
