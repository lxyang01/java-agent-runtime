package io.github.lxyang01.agent.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.util.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void asMap_omits_null_name_and_tool_call_id() {
        var m = ChatMessage.user("你好");
        assertThat(m.asMap()).hasSize(2)
            .containsEntry("role", "user")
            .containsEntry("content", "你好");
    }

    @Test
    void asMap_keeps_wire_key_names() {
        var m = new ChatMessage(ChatRole.TOOL, "结果", "bill.query", "abc123");
        assertThat(m.asMap()).containsEntry("role", "tool")
            .containsEntry("name", "bill.query")
            .containsEntry("tool_call_id", "abc123");
    }

    @Test
    void json_roundtrip_preserves_role_and_optional_fields() {
        var original = new ChatMessage(ChatRole.TOOL, "结果", "bill.query", "abc123");
        var copy = Json.MAPPER.convertValue(original.asMap(), ChatMessage.class);
        assertThat(copy).isEqualTo(original);

        var plain = ChatMessage.system("规则");
        var copyPlain = Json.MAPPER.convertValue(plain.asMap(), ChatMessage.class);
        assertThat(copyPlain).isEqualTo(plain);
    }

    @Test
    void role_wire_values_are_lowercase_strings() {
        assertThat(ChatRole.SYSTEM.wire()).isEqualTo("system");
        assertThat(ChatRole.from("assistant")).isEqualTo(ChatRole.ASSISTANT);
    }
}

class ConversationTest {

    @Test
    void new_conversation_starts_empty() {
        var c = Conversation.newConversation("s1");
        assertThat(c.getSessionId()).isEqualTo("s1");
        assertThat(c.messages()).isEmpty();
        assertThat(c.getSummary()).isEmpty();
        assertThat(c.getOwner()).isNull();
    }

    @Test
    void add_message_appends_in_order() {
        var c = Conversation.newConversation("s1");
        c.addMessage(ChatMessage.user("问题"));
        c.addMessage(ChatMessage.assistant("回答"));
        assertThat(c.messages()).hasSize(2);
        assertThat(c.messages().get(0).role()).isEqualTo(ChatRole.USER);
        assertThat(c.messages().get(1).role()).isEqualTo(ChatRole.ASSISTANT);
    }

    @Test
    void messages_view_is_unmodifiable() {
        var c = Conversation.newConversation("s1");
        c.addMessage(ChatMessage.user("x"));
        assertThatThrownBy(() -> c.messages().add(ChatMessage.user("y")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void replace_messages_decouples_from_original_list() {
        var c = Conversation.newConversation("s1");
        c.addMessage(ChatMessage.user("x"));
        List<ChatMessage> replacement = new java.util.ArrayList<>();
        replacement.add(ChatMessage.user("y"));
        c.replaceMessages(replacement);
        replacement.add(ChatMessage.user("z"));
        assertThat(c.messages()).hasSize(1);
    }

    @Test
    void with_summary_and_messages_returns_new_instance() {
        var c = Conversation.newConversation("s1");
        c.addMessage(ChatMessage.user("旧"));
        var compressed = c.withSummaryAndMessages("- 问:旧", List.of(ChatMessage.user("新")));
        assertThat(compressed).isNotSameAs(c);
        assertThat(compressed.getSummary()).isEqualTo("- 问:旧");
        assertThat(compressed.messages()).hasSize(1);
        // 原实例不受影响(压缩是纯函数语义)
        assertThat(c.getSummary()).isEmpty();
        assertThat(c.messages()).hasSize(1);
    }
}
