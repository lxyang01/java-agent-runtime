package io.github.lxyang01.billguard.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP 服务端认证(fail-closed):协议端点要求 X-BillGuard-Api-Key 与配置的
 * 服务密钥恒时相等;未配置密钥时拒绝所有调用(显式选择开放需不注册本过滤器)。
 * 本类只做认证与拒绝,不做身份传递 —— 认证身份(X-BillGuard-Owner)由
 * contextExtractor 随调用流入工具处理层,以认证身份覆盖客户端传入的 owner,
 * 租户隔离不依赖客户端自觉。由各 MCP server 装配显式注册(见应用类),
 * 不用组件扫描:避免被同包基线的其它应用误装配。
 */
public class McpAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-BillGuard-Api-Key";
    public static final String OWNER_HEADER = "X-BillGuard-Owner";

    private final String apiKey;

    public McpAuthFilter(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (apiKey.isEmpty()) {
            // fail-closed:未配置密钥 = 服务不可用,而不是无认证开放
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json; charset=utf-8");
            response.getWriter().write("{\"error\":\"MCP 服务未配置访问密钥,拒绝服务\"}");
            return;
        }
        String provided = request.getHeader(API_KEY_HEADER);
        if (provided == null || !constantTimeEquals(provided, apiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json; charset=utf-8");
            response.getWriter().write("{\"error\":\"MCP 认证失败\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
