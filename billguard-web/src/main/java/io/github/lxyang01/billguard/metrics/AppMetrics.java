package io.github.lxyang01.billguard.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 进程内指标(Micrometer 注册表,扁平命名与 metrics.py 对齐:
 * http_requests_total / http_status_{code} / login_failures_total /
 * login_throttle_blocks_total / approvals_decided_total / lock_conflicts_total /
 * llm_calls_total / llm_failures_total / tool_calls_total / tool_failures_total;
 * 计时 http_request_seconds / llm_call_seconds;仪表 llm_slots_in_use)。
 */
@Component
public class AppMetrics {

    private final MeterRegistry registry;
    private final long startedAt = System.nanoTime();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final AtomicLong llmSlotsInUse = new AtomicLong();

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("llm_slots_in_use", llmSlotsInUse);
    }

    public void inc(String name) {
        counters.computeIfAbsent(name,
            key -> Counter.builder(key).register(registry)).increment();
    }

    public void observe(String name, double seconds) {
        timers.computeIfAbsent(name,
            key -> Timer.builder(key).register(registry)).record((long) (seconds * 1_000_000),
                TimeUnit.MICROSECONDS);
    }

    public void llmSlotAcquired() {
        llmSlotsInUse.incrementAndGet();
    }

    public void llmSlotReleased() {
        llmSlotsInUse.decrementAndGet();
    }

    /** JSON 快照:形状指标快照(counters/timings/gauges 三段)。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> countersView = new LinkedHashMap<>();
        counters.forEach((name, counter) -> countersView.put(name,
            (long) counter.count()));
        Map<String, Object> timings = new LinkedHashMap<>();
        timers.forEach((name, timer) -> {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("count", timer.count());
            slot.put("sum", Math.round(timer.totalTime(TimeUnit.SECONDS) * 1e6) / 1e6);
            timings.put(name, slot);
        });
        Map<String, Object> gauges = new LinkedHashMap<>();
        gauges.put("llm_slots_in_use", llmSlotsInUse.get());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uptime_seconds",
            Math.round(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)) / 1000.0);
        result.put("counters", countersView);
        result.put("gauges", gauges);
        result.put("timings", timings);
        return result;
    }
}
