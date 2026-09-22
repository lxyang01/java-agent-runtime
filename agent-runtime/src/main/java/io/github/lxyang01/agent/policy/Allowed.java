package io.github.lxyang01.agent.policy;

/** 放行裁决(单例)。 */
public record Allowed() implements PolicyVerdict {
    public static final Allowed INSTANCE = new Allowed();
}
