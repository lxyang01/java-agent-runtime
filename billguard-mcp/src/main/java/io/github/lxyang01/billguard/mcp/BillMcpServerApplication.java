package io.github.lxyang01.billguard.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** bill MCP server(账单数据;--spring.profiles.active=bill)。 */
@SpringBootApplication
public class BillMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillMcpServerApplication.class, args);
    }
}
