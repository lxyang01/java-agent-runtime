package io.github.lxyang01.agent.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会话(持久化单元)。messages 可变(引擎按步追加),对外暴露不可变视图;
 * {@link #withSummaryAndMessages} 返回新实例 —— 历史压缩是纯函数,不就地改写。
 */
public final class Conversation {

    private final String sessionId;
    private String summary;
    private String owner;
    private final List<ChatMessage> messages;

    public Conversation(String sessionId, String summary, String owner, List<ChatMessage> messages) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.summary = summary == null ? "" : summary;
        this.owner = owner;
        this.messages = new ArrayList<>(messages);
    }

    public static Conversation newConversation(String sessionId) {
        return new Conversation(sessionId, "", null, List.of());
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public String getOwner() {
        return owner;
    }

    /** 会话认领:首个访问的登录用户成为归属(仅当尚未分配)。 */
    public void setOwner(String owner) {
        this.owner = owner;
    }

    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
    }

    public void replaceMessages(List<ChatMessage> replacement) {
        messages.clear();
        messages.addAll(replacement);
    }

    /** 返回新实例(压缩语义:入参不变)。 */
    public Conversation withSummaryAndMessages(String newSummary, List<ChatMessage> newMessages) {
        return new Conversation(sessionId, newSummary, owner, newMessages);
    }
}
