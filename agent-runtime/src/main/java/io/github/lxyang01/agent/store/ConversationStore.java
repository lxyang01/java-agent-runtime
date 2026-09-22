package io.github.lxyang01.agent.store;

import io.github.lxyang01.agent.types.Conversation;

/** 会话存取端口(PG 实现见 billguard-web storage 包)。 */
public interface ConversationStore {

    /** 不存在的会话返回空会话(而非 null)。 */
    Conversation load(String sessionId);

    void save(Conversation conversation);
}
