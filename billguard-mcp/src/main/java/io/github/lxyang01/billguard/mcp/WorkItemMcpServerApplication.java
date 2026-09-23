package io.github.lxyang01.billguard.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** work-item MCP server(工单三阶段;--spring.profiles.active=work-item)。 */
@SpringBootApplication
public class WorkItemMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkItemMcpServerApplication.class, args);
    }
}
