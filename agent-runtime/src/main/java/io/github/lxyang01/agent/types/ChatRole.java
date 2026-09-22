package io.github.lxyang01.agent.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 会话消息角色;wire 值为小写字符串(sessions 表 messages JSONB 契约)。 */
public enum ChatRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wire;

    ChatRole(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ChatRole from(String wire) {
        for (ChatRole role : values()) {
            if (role.wire.equals(wire)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown message role: " + wire);
    }
}
