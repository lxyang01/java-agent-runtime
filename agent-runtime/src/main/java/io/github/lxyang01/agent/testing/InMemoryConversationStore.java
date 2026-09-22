package io.github.lxyang01.agent.testing;

import io.github.lxyang01.agent.store.ConversationStore;
import io.github.lxyang01.agent.types.Conversation;
import java.util.LinkedHashMap;
import java.util.Map;

/** 进程内会话存储(测试用)。 */
public final class InMemoryConversationStore implements ConversationStore {

    private final Map<String, Conversation> conversations = new LinkedHashMap<>();

    @Override
    public Conversation load(String sessionId) {
        Conversation saved = conversations.get(sessionId);
        return saved != null ? saved : Conversation.newConversation(sessionId);
    }

    @Override
    public void save(Conversation conversation) {
        conversations.put(conversation.getSessionId(), conversation);
    }

    public boolean exists(String sessionId) {
        return conversations.containsKey(sessionId);
    }
}
