package io.github.lxyang01.billguard.eval;

import java.util.function.Function;

/** 一条对抗探针的声明(id/类别/严重度/标题/攻击/期望/修复建议逐字对齐 )。 */
public record AdversarialCase(
    String id,
    String category,
    String severity,
    String title,
    String attack,
    String expected,
    String remediation,
    Function<Object, ProbeResult> probe) {
}
