package io.github.lxyang01.agent.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 会话消息。序列化键名 {@code role/content/name?/tool_call_id?} —— sessions 表 messages
 * JSONB 的既有形状,与 Python 版 Message.as_dict 一致。
 */
public record ChatMessage(
    @JsonProperty("role") ChatRole role,
    @JsonProperty("content") String content,
    @JsonProperty("name") String name,
    @JsonProperty("tool_call_id") String toolCallId) {

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null);
    }

    public static ChatMessage tool(String name, String content, String callId) {
        return new ChatMessage(ChatRole.TOOL, content, name, callId);
    }

    /** 模型可见面(wire)形状:null 字段省略。 */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role.wire());
        map.put("content", content);
        if (name != null) {
            map.put("name", name);
        }
        if (toolCallId != null) {
            map.put("tool_call_id", toolCallId);
        }
        return map;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChatMessage other
            && role == other.role
            && Objects.equals(content, other.content)
            && Objects.equals(name, other.name)
            && Objects.equals(toolCallId, other.toolCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content, name, toolCallId);
    }
}
