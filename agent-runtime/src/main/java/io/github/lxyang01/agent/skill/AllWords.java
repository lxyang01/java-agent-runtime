package io.github.lxyang01.agent.skill;

import java.util.List;

/** 共现触发词组:全部词都出现才命中(顺序无关),得分 = 词长之和。 */
public record AllWords(List<String> words) implements Trigger {
}
