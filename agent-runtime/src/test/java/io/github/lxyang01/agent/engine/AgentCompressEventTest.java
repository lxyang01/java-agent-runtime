package io.github.lxyang01.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.llm.LlmResult;
import io.github.lxyang01.agent.types.ChatMessage;
import io.github.lxyang01.agent.types.Conversation;
import io.github.lxyang01.agent.types.RunEvents;
import io.github.lxyang01.agent.util.Json;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Task 13:历史压缩、超时止损与事件序列。 */
class AgentCompressEventTest {

    EngineHarness harness = new EngineHarness();

    AgentSpec spec = AgentSpec.builder("demo", "i",
        List.of("demo.lookup", "demo.write")).build();

    @Test
    void compress_history_summarizes_qa_pairs() {
        Conversation conversation = Conversation.newConversation("s1");
        conversation.addMessage(ChatMessage.user("第一个问题,比较长 ".repeat(10)));
        conversation.addMessage(ChatMessage.tool("demo.lookup", "{\"count\":3}", "c1"));
        conversation.addMessage(ChatMessage.assistant("{\"final\":\"第一个回答\"}"));
        conversation.addMessage(ChatMessage.user("第二个问题"));
        conversation.addMessage(ChatMessage.assistant("第二个回答"));
        conversation.setSummary("- 问:更早的问题 答:更早的回答");
        // keep_recent=2:保留最后 2 条(第二问+第二答),前 3 条折叠
        Conversation compressed = AgentRuntime.compressHistory(conversation, 2);

        assertThat(compressed).isNotSameAs(conversation);
        assertThat(compressed.getSummary()).contains("- 问:更早的问题 答:更早的回答");
        assertThat(compressed.getSummary()).contains("- 问:第一个问题");
        assertThat(compressed.getSummary()).contains("答:第一个回答");
        assertThat(compressed.messages()).hasSize(2);
        assertThat(compressed.messages().get(0).content()).isEqualTo("第二个问题");
        // 入参不变(纯函数语义)
        assertThat(conversation.messages()).hasSize(5);
        assertThat(conversation.getSummary()).isEqualTo("- 问:更早的问题 答:更早的回答");
    }

    @Test
    void compress_history_unanswered_question_logged() {
        Conversation conversation = Conversation.newConversation("s1");
        conversation.addMessage(ChatMessage.user("没人答的问题"));
        conversation.addMessage(ChatMessage.user("新问题"));
        conversation.addMessage(ChatMessage.assistant("新回答"));
        Conversation compressed = AgentRuntime.compressHistory(conversation, 2);
        assertThat(compressed.getSummary())
            .contains("- 问:没人答的问题 答:(该轮无最终回答)");
    }

    @Test
    void compression_triggered_at_threshold() {
        var tight = AgentSpec.builder("demo", "i", spec.toolNames())
            .summaryThreshold(4).build();
        Conversation seeded = Conversation.newConversation("s1");
        for (int i = 0; i < 5; i++) {
            seeded.addMessage(ChatMessage.user("问题" + i));
            seeded.addMessage(ChatMessage.assistant("回答" + i));
        }
        harness.conversations.save(seeded);
        var response = harness.runtime(tight,
            new io.github.lxyang01.agent.testing.FinalLlm("好的")).run("s1", "新输入");
        assertThat(response.status()).isEqualTo("completed");
        assertThat(harness.trace.records().stream().anyMatch(r ->
            RunEvents.HISTORY_COMPRESSED.equals(r.get("event")))).isTrue();
    }

    @Test
    void run_timeout_aborts() {
        var timed = AgentSpec.builder("demo", "i", spec.toolNames())
            .runTimeout(java.time.Duration.ofMillis(50)).build();
        AtomicInteger calls = new AtomicInteger();
        LlmClient slow = request -> {
            calls.incrementAndGet();
            sleepQuiet(100);
            return new LlmResult(
                Json.write(Map.of("tool_call",
                    Map.of("name", "demo.lookup", "arguments", Map.of()))),
                Map.of(), "slow");
        };
        var response = harness.runtime(timed, slow).run("s1", "慢查询");
        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.answer()).contains("已达到最大执行时间");
        assertThat(harness.trace.records().stream().anyMatch(r ->
            RunEvents.RUN_TIMEOUT.equals(r.get("event")))).isTrue();
    }

    @Test
    void event_sequence_for_full_run() {
        var llm = new io.github.lxyang01.agent.testing.ScriptedLlm(
            Map.of("tool_call", Map.of("name", "demo.lookup", "arguments", Map.of())),
            Map.of("final", "完成"));
        var response = harness.runtime(spec, llm).run("s1", "查一下");
        assertThat(response.status()).isEqualTo("completed");
        var sequence = harness.trace.records().stream()
            .map(r -> String.valueOf(r.get("event")))
            .filter(event -> !event.equals(RunEvents.HISTORY_COMPRESSED))
            .toList();
        assertThat(sequence).containsExactly(
            RunEvents.RUN_START,
            RunEvents.MODEL_START, RunEvents.MODEL_OUTPUT, RunEvents.MODEL_DECISION,
            RunEvents.TOOL_START, RunEvents.TOOL_END,
            RunEvents.MODEL_START, RunEvents.MODEL_OUTPUT, RunEvents.MODEL_DECISION,
            RunEvents.RUN_END);
    }

    @Test
    void hooks_and_trace_both_receive_events() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        var llm = new io.github.lxyang01.agent.testing.FinalLlm("收到");
        var runtime = harness.builder(spec, llm)
            .approvals(new io.github.lxyang01.agent.testing.InMemoryApprovalStore())
            .hook(event -> {
            seen.add(event.eventType());
            if (event.eventType().equals(RunEvents.MODEL_START)) {
                throw new IllegalStateException("hook 故障必须被吞没");
            }
        }).build();
        var response = runtime.run("s1", "你好");
        // hook 异常不破坏主循环
        assertThat(response.status()).isEqualTo("completed");
        assertThat(seen).contains(RunEvents.MODEL_START, RunEvents.RUN_END);
        assertThat(harness.trace.records()).isNotEmpty();
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
