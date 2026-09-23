package io.github.lxyang01.billguard.storage;

import io.github.lxyang01.agent.store.TraceWriter;
import io.github.lxyang01.agent.types.Timestamps;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PG 追踪写入:FOR UPDATE 锁行读-改-写追加,事务内 upsert(PGTraceStore)。
 * 写入只发生在 web 会话锁内的 run/resume 期间(单写者),FOR UPDATE 规避并发覆盖。
 */
public final class PgTraceWriter implements TraceWriter {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PgTraceWriter(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void appendEvent(String sessionId, String traceId, String agent,
                            Map<String, Object> record) {
        tx.executeWithoutResult(status -> {
            List<com.fasterxml.jackson.databind.JsonNode> rows = jdbc.query(
                "SELECT events FROM traces WHERE session_id = ? AND trace_id = ? FOR UPDATE",
                (rs, rowNum) -> io.github.lxyang01.agent.util.Json.readTree(
                    rs.getString("events")),
                sessionId, traceId);
            List<Object> events = new java.util.ArrayList<>();
            if (!rows.isEmpty() && rows.get(0) != null && rows.get(0).isArray()) {
                rows.get(0).forEach(node -> events.add(
                    io.github.lxyang01.agent.util.Json.MAPPER.convertValue(node,
                        new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.LinkedHashMap<String, Object>>() {})));
            }
            events.add(record);
            jdbc.update("""
                INSERT INTO traces(session_id, trace_id, agent, events, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id, trace_id) DO UPDATE SET events = EXCLUDED.events
                """,
                sessionId, traceId, agent == null ? "" : agent, PgJson.value(events),
                Timestamps.nowIso());
        });
    }
}
