package io.github.lxyang01.agent.llm;

import io.github.lxyang01.agent.types.ChatMessage;
import java.util.List;
import java.util.Map;

/** 单次模型调用输入:工作消息 + 工具 schema 列表({name,description,parameters})。 */
public record LlmRequest(List<ChatMessage> messages, List<Map<String, Object>> toolSchemas) {
}
