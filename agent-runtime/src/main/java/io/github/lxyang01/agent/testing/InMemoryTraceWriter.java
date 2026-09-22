package io.github.lxyang01.agent.testing;

import io.github.lxyang01.agent.store.TraceWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 进程内追踪写入(测试断言事件序列用)。 */
public final class InMemoryTraceWriter implements TraceWriter {

    private final List<Map<String, Object>> records = new ArrayList<>();

    @Override
    public void appendEvent(String sessionId, String traceId, String agent,
                            Map<String, Object> record) {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("session_id", sessionId);
        copy.put("trace_id", traceId);
        copy.put("agent", agent);
        copy.putAll(record);
        records.add(copy);
    }

    public List<Map<String, Object>> records() {
        return List.copyOf(records);
    }

    public List<Map<String, Object>> records(String sessionId, String traceId) {
        return records.stream()
            .filter(record -> sessionId.equals(record.get("session_id"))
                && traceId.equals(record.get("trace_id")))
            .toList();
    }
}
