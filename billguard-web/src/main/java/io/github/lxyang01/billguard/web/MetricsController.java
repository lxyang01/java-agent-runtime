package io.github.lxyang01.billguard.web;

import io.github.lxyang01.billguard.metrics.AppMetrics;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/metrics:本实例指标快照。与其它 API 一致要求登录,但不设能力门槛
 * —— 指标是运行操作数据而非敏感业务数据,任何登录用户可读;未登录 401。
 */
@RestController
public class MetricsController {

    private final AppMetrics metrics;
    private final String instance;

    public MetricsController(AppMetrics metrics,
        @Value("${server.port:8080}") int port) {
        this.metrics = metrics;
        this.instance = "127.0.0.1:" + port;
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instance", instance);
        body.putAll(metrics.snapshot());
        body.put("note", "生产环境可将此 JSON 转换为 Prometheus 文本格式抓取"
            + "(见 docs/development.md);Actuator /actuator/prometheus 亦可直接暴露");
        return body;
    }
}
