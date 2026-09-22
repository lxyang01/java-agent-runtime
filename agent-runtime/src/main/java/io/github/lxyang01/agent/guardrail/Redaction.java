package io.github.lxyang01.agent.guardrail;

import java.util.Map;

/** PII 脱敏结果:counts 按类型统计替换次数(事件 output_redacted 的 redactions 字段)。 */
public record Redaction(String text, Map<String, Integer> counts) {

    public static Redaction of(String text, Map<String, Integer> counts) {
        return new Redaction(text, counts);
    }
}
