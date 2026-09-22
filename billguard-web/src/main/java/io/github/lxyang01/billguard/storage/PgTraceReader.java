package io.github.lxyang01.billguard.storage;

import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.util.Json;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** traces 表只读投影:run 列表与详情(摘要逻辑逐字对齐 observability.TraceStore)。 */
public final class PgTraceReader {

    private final JdbcTemplate jdbc;

    public PgTraceReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRuns(String sessionId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("run list limit must be between 1 and 200");
        }
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> event : readEvents(sessionId)) {
            String traceId = String.valueOf(event.getOrDefault("trace_id", ""));
            if (!traceId.isEmpty()) {
                groups.computeIfAbsent(traceId, k -> new ArrayList<>()).add(event);
            }
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        groups.forEach((traceId, events) -> runs.add(summary(traceId, events)));
        runs.sort(Comparator.comparing(run -> String.valueOf(run.get("started_at")),
            Comparator.reverseOrder()));
        return runs.subList(0, Math.min(limit, runs.size()));
    }

    public Map<String, Object> getRun(String sessionId, String traceId) {
        String safeTraceId = traceId == null ? "" : traceId.strip();
        if (safeTraceId.isEmpty()) {
            throw new IllegalArgumentException("trace_id is required");
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> event : readEvents(sessionId)) {
            if (safeTraceId.equals(event.get("trace_id"))) {
                events.add(event);
            }
        }
        if (events.isEmpty()) {
            throw new PolicyException("run not found: " + safeTraceId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary(safeTraceId, events));
        result.put("events", events);
        return result;
    }

    private List<Map<String, Object>> readEvents(String sessionId) {
        List<Map<String, Object>> events = new ArrayList<>();
        jdbc.query("SELECT events FROM traces WHERE session_id = ?", rs -> {
            for (var element : Json.readTree(rs.getString("events"))) {
                if (element.isObject()) {
                    Map<String, Object> event = Json.MAPPER.convertValue(element,
                        new com.fasterxml.jackson.core.type.TypeReference<
                            LinkedHashMap<String, Object>>() {});
                    event.putIfAbsent("session_id", sessionId);
                    events.add(event);
                }
            }
        }, sessionId);
        return events;
    }

    /** run 摘要:状态推导/活跃时长/token 累计(对齐 Python _summary)。 */
    private static Map<String, Object> summary(String traceId, List<Map<String, Object>> input) {
        List<Map<String, Object>> ordered = new ArrayList<>(input);
        ordered.sort(Comparator.comparing(event -> String.valueOf(event.getOrDefault("timestamp",
            ""))));
        String startedAt = ordered.isEmpty() ? ""
            : String.valueOf(ordered.get(0).getOrDefault("timestamp", ""));
        String endedAt = ordered.isEmpty() ? ""
            : String.valueOf(ordered.get(ordered.size() - 1).getOrDefault("timestamp", ""));
        String status = "running";
        for (Map<String, Object> event : ordered) {
            String name = String.valueOf(event.get("event"));
            status = switch (name) {
                case "approval_pending" -> "approval_pending";
                case "approval_rejected" -> "rejected";
                case "run_error", "max_steps" -> "failed";
                case "run_end" -> String.valueOf(event.getOrDefault("status", "completed"));
                default -> status;
            };
        }
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        List<String> tools = new ArrayList<>();
        double activeMs = 0;
        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("prompt_tokens", 0);
        tokenUsage.put("completion_tokens", 0);
        tokenUsage.put("total_tokens", 0);
        double cost = 0;
        int modelCalls = 0;
        int completionBlocks = 0;
        boolean resumed = false;
        int maxStep = 0;
        String agent = "";
        for (Map<String, Object> event : ordered) {
            String name = String.valueOf(event.get("event"));
            if ("skill_activated".equals(name) && event.get("skill") != null) {
                skills.add(String.valueOf(event.get("skill")));
            } else if ("tool_start".equals(name) && event.get("tool") != null) {
                tools.add(String.valueOf(event.get("tool")));
            } else if (List.of("model_output", "tool_end", "tool_error").contains(name)) {
                activeMs += asDouble(event.get("latency_ms"));
            } else if ("model_start".equals(name)) {
                modelCalls++;
            } else if ("completion_blocked".equals(name)) {
                completionBlocks++;
            } else if ("run_resume".equals(name)) {
                resumed = true;
            }
            if (event.get("agent") != null && agent.isEmpty()) {
                agent = String.valueOf(event.get("agent"));
            }
            Object step = event.get("step");
            if (step instanceof Number number) {
                maxStep = Math.max(maxStep, number.intValue());
            }
            if (event.get("usage") instanceof Map<?, ?> usageMap) {
                for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
                    tokenUsage.put(key, ((Number) tokenUsage.get(key)).intValue()
                        + (int) asDouble(usageMap.get(key)));
                }
                cost += asDouble(usageMap.get("cost"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trace_id", traceId);
        result.put("session_id", ordered.isEmpty() ? ""
            : String.valueOf(ordered.get(0).getOrDefault("session_id", "")));
        result.put("agent", agent);
        result.put("status", status);
        result.put("started_at", startedAt);
        result.put("ended_at", endedAt);
        result.put("wall_time_ms", durationMs(startedAt, endedAt));
        result.put("active_time_ms", Math.round(activeMs * 100.0) / 100.0);
        result.put("steps", maxStep);
        result.put("model_calls", modelCalls);
        result.put("tool_calls", tools.size());
        result.put("tools", tools);
        result.put("skills", new ArrayList<>(skills));
        result.put("completion_blocks", completionBlocks);
        result.put("resumed", resumed);
        result.put("token_usage", tokenUsage);
        result.put("cost", Math.round(cost * 1e8) / 1e8);
        return result;
    }

    private static double durationMs(String startedAt, String endedAt) {
        if (startedAt == null || startedAt.isEmpty() || endedAt == null || endedAt.isEmpty()) {
            return 0.0;
        }
        try {
            double ms = (OffsetDateTime.parse(endedAt).toInstant().toEpochMilli()
                - OffsetDateTime.parse(startedAt).toInstant().toEpochMilli());
            return Math.max(0.0, Math.round(ms * 100.0) / 100.0);
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
