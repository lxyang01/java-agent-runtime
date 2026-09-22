package io.github.lxyang01.agent.store;

import java.util.Map;

/** 追踪事件写入端口(traces 表 events JSONB 读-改-写追加)。 */
public interface TraceWriter {

    void appendEvent(String sessionId, String traceId, String agent, Map<String, Object> record);
}
