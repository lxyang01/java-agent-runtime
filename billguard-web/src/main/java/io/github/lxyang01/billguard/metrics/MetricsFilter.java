package io.github.lxyang01.billguard.metrics;

import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JSON API 指标:仅统计 /api/** 响应(http_requests_total / http_status_{code} /
 * http_request_seconds);/api/metrics 自身不计时(抓取间隔不反馈进时延统计)。
 * 经 @Bean 注册而非 @Component:@WebMvcTest 切片会拾取组件过滤器并连带
 * MeterRegistry 依赖,bean 注册则留在主上下文。
 */
public class MetricsFilter extends OncePerRequestFilter {

    private final AppMetrics metrics;

    public MetricsFilter(AppMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean isApi = request.getRequestURI().startsWith("/api/");
        boolean isMetrics = "/api/metrics".equals(request.getRequestURI());
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (isApi) {
                metrics.inc("http_requests_total");
                metrics.inc("http_status_" + response.getStatus());
                if (!isMetrics) {
                    metrics.observe("http_request_seconds",
                        (System.nanoTime() - started) / 1e9);
                }
            }
        }
    }

    /** 注册为最低优先级过滤器(主上下文;切片测试不加载)。 */
    @Configuration
    public static class Registration {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        public MetricsFilter metricsFilter(AppMetrics metrics) {
            return new MetricsFilter(metrics);
        }
    }
}
