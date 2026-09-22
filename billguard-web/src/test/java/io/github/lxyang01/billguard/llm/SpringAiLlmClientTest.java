package io.github.lxyang01.billguard.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.types.ChatMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import io.github.lxyang01.agent.util.Json;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 适配器单测(stub ChatModel):wire 改写、schema 注入位、usage 携带。 */
class SpringAiLlmClientTest {

    static class CapturingModel implements ChatModel {
        List<Message> lastPrompt;
        OpenAiChatOptions lastOptions;

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt.getInstructions();
            lastOptions = (OpenAiChatOptions) prompt.getOptions();
            Generation generation = new Generation(new AssistantMessage("{\"final\":\"好\"}"),
                ChatGenerationMetadata.builder().finishReason("STOP").build());
            return new ChatResponse(List.of(generation));
        }
    }

    @Test
    void wire_rewrites_tool_role_and_injects_schema_after_first_system() {
        var model = new CapturingModel();
        var client = new SpringAiLlmClient(model, "gpt-4.1-mini", null);
        var request = new LlmRequest(List.of(
            ChatMessage.system("你是守卫。"),
            ChatMessage.user("查一下"),
            new io.github.lxyang01.agent.types.ChatMessage(
                io.github.lxyang01.agent.types.ChatRole.ASSISTANT, "{\"tool_call\":{}}",
                null, "c1"),
            ChatMessage.tool("bill.query", "{\"count\":3}", "c1")),
            List.of(Map.of("name", "bill.query", "description", "查询",
                "parameters", Map.of())));

        var result = client.complete(request);
        assertThat(result.raw()).isEqualTo("{\"final\":\"好\"}");
        assertThat(result.model()).isEqualTo("gpt-4.1-mini");

        assertThat(model.lastPrompt).hasSize(5);
        // assistant 角色保留(Python wire 仅改写 tool)
        assertThat(model.lastPrompt.get(3))
            .isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        assertThat(model.lastPrompt.get(0).getText()).isEqualTo("你是守卫。");
        // schema system 紧跟首条 system
        assertThat(model.lastPrompt.get(1).getText())
            .startsWith("可用工具 JSON Schema：").contains("bill.query");
        // tool 消息改写为 user,带工具名前缀
        assertThat(model.lastPrompt.get(4).getText())
            .startsWith("[工具 bill.query 的执行结果]\n{\"count\":3}");
        // response_format = json_object
        assertThat(model.lastOptions.getResponseFormat().getType())
            .isEqualTo(org.springframework.ai.openai.api.ResponseFormat.Type.JSON_OBJECT);
        assertThat(model.lastOptions.getModel()).isEqualTo("gpt-4.1-mini");
    }

    @Test
    void temperature_forwarded_when_present() {
        var model = new CapturingModel();
        new SpringAiLlmClient(model, "m", 0.3)
            .complete(new LlmRequest(List.of(ChatMessage.user("hi")), List.of()));
        assertThat(model.lastOptions.getTemperature()).isEqualTo(0.3);
    }
}
