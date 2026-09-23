package io.github.lxyang01.billguard.llm;

import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.llm.LlmResult;
import io.github.lxyang01.agent.types.ChatMessage;
import io.github.lxyang01.agent.types.ChatRole;
import io.github.lxyang01.agent.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

/**
 * LlmClient 的生产实现:Spring AI OpenAI 兼容 ChatModel(OpenRouter 可用)。
 * wire 改写逐字
 * "[工具 X 的执行结果]";工具 schema 序列化为一条 system 消息插在首条 system 之后;
 * response_format 固定 json_object(引擎按 JSON 决策协议解析)。
 */
public final class SpringAiLlmClient implements LlmClient {

    private final ChatModel chatModel;
    private final String model;
    private final Double temperature;

    public SpringAiLlmClient(ChatModel chatModel, String model, Double temperature) {
        this.chatModel = chatModel;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        List<org.springframework.ai.chat.messages.Message> wire = new ArrayList<>();
        boolean inserted = false;
        for (ChatMessage message : request.messages()) {
            if (message.role() == ChatRole.TOOL) {
                wire.add(new UserMessage("[工具 " + orDefault(message.name(), "unknown")
                    + " 的执行结果]\n" + message.content()));
                continue;
            }
            if (message.role() == ChatRole.SYSTEM && !inserted) {
                wire.add(new SystemMessage(message.content()));
                // 工具 schema 紧跟首条 system(与 enriched.insert(1, ...) 同位)
                if (!request.toolSchemas().isEmpty()) {
                    wire.add(new SystemMessage("可用工具 JSON Schema：\n"
                        + Json.write(request.toolSchemas())));
                }
                inserted = true;
                continue;
            }
            wire.add(message.role() == ChatRole.SYSTEM
                ? new SystemMessage(message.content())
                : message.role() == ChatRole.ASSISTANT
                    ? new org.springframework.ai.chat.messages.AssistantMessage(message.content())
                    : new UserMessage(message.content()));
        }

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
            .model(model)
            .responseFormat(jsonObjectFormat());
        if (temperature != null) {
            options.temperature(temperature);
        }
        ChatResponse response = chatModel.call(new Prompt(wire, options.build()));
        String raw = response.getResult().getOutput().getText();
        Map<String, Object> usage = new LinkedHashMap<>();
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            usage.put("prompt_tokens", u.getPromptTokens());
            usage.put("completion_tokens", u.getCompletionTokens());
            usage.put("total_tokens", u.getTotalTokens());
        }
        String respondedModel = model;
        return new LlmResult(raw == null ? "" : raw, usage, respondedModel);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static ResponseFormat jsonObjectFormat() {
        ResponseFormat format = new ResponseFormat();
        format.setType(ResponseFormat.Type.JSON_OBJECT);
        return format;
    }

    /** ChatClient 兼容入口(装配层使用;默认选项与 complete 一致)。 */
    public ChatClient asChatClient() {
        return ChatClient.create(chatModel);
    }
}
