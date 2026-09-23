package io.github.lxyang01.billguard.eval;

import java.util.Map;

/** 探针结果:protected=攻击被遏制;observed/evidence 进报告(ProbeResult)。 */
public record ProbeResult(boolean protected_, String observed, Map<String, Object> evidence) {

    public static ProbeResult of(boolean protected_, String observed,
                                 Map<String, Object> evidence) {
        return new ProbeResult(protected_, observed, evidence);
    }
}
