package io.github.lxyang01.billguard.mcp;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * MCP 服务端认证装配:仅 bill / work-item 两个 server profile 激活。
 * 显式 FilterRegistrationBean 而非 @Component —— 本模块与 web 应用共享
 * io.github.lxyang01.billguard 包基线,组件扫描会把过滤器误装进 web;
 * profile 门 + 显式注册保证认证边界只出现在 MCP server 装配里。
 * 只覆盖 /mcp:健康检查保持开放供编排探活。密钥未配置时过滤器自身
 * fail-closed(全部调用 503),不存在无认证开放形态。
 */
@Configuration
@Profile({"bill", "work-item"})
public class McpSecurityConfig {

    @Bean
    FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(Environment env) {
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(
            new McpAuthFilter(env.getProperty("BILLGUARD_MCP_API_KEY", "")));
        registration.addUrlPatterns("/mcp");
        registration.setOrder(1);
        return registration;
    }
}
