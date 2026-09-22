package io.github.lxyang01.agent.types;

import java.util.Map;

/**
 * 运行事件。trace 的 wire 契约是「字符串事件名 + data map」(traces 表 events JSONB),
 * 因此这里用 record + 事件名常量(RunEvents),不做 sealed 变体。
 * hook 消费方异常必须被引擎吞没 —— observability must never break the loop。
 */
public record RunEvent(
    String eventType,
    String traceId,
    String sessionId,
    int step,
    Map<String, Object> data,
    String timestamp) {

    public static RunEvent of(String eventType, String traceId, String sessionId,
                              int step, Map<String, Object> data) {
        return new RunEvent(eventType, traceId, sessionId, step, data, Timestamps.nowIso());
    }
}
