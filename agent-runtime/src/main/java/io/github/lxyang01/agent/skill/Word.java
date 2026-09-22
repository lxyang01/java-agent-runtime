package io.github.lxyang01.agent.skill;

/** 单字符串触发词:按子串命中。 */
public record Word(String value) implements Trigger {
}
