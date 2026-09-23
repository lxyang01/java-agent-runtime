package io.github.lxyang01.agent.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 工具风险级别;wire 值与 参考实现字符串一致。 */
public enum RiskLevel {
    READ("read"),
    LOW_WRITE("low_write"),
    HIGH_WRITE("high_write"),
    FORBIDDEN("forbidden");

    private final String wire;

    RiskLevel(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RiskLevel from(String wire) {
        for (RiskLevel level : values()) {
            if (level.wire.equals(wire)) {
                return level;
            }
        }
        throw new PolicyException("invalid risk level: " + wire);
    }
}
