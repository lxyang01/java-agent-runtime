package io.github.lxyang01.agent.types;

import java.util.List;
import java.util.Map;

/**
 * 一次 run/resume 的对外结果。
 * approval 仅在 status=approval_pending 时非空,形状 = ApprovalRequest.as_dict(不含 checkpoint)。
 */
public record AgentResponse(
    String answer,
    int steps,
    String traceId,
    List<String> activeSkills,
    String status,
    Map<String, Object> approval) {

    public static AgentResponse of(String answer, int steps, String traceId,
                                   List<String> activeSkills, String status) {
        return new AgentResponse(answer, steps, traceId, activeSkills, status, null);
    }
}
