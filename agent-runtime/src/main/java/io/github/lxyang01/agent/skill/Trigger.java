package io.github.lxyang01.agent.skill;

/** 触发词:字符串按子串命中;数组按共现(全词出现,顺序无关)。 */
public sealed interface Trigger permits Word, AllWords {
}
