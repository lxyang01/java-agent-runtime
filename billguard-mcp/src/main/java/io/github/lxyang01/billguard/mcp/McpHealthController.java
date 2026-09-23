package io.github.lxyang01.billguard.mcp;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 就绪探针(compose healthcheck):只确认进程与 HTTP 栈存活,不触存储。 */
@RestController
public class McpHealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }
}
