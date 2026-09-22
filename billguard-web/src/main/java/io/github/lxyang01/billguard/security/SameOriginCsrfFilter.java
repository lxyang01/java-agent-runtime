package io.github.lxyang01.billguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF 同源校验:仅作用于 POST(登录除外 —— 登录在自己的处理器里按
 * 「参数校验 → 同源校验」的顺序内联执行);仅当请求已认证时拒绝 ——
 * 未认证请求由授权层先行 401(对齐 Python 的检查顺序)。
 */
public class SameOriginCsrfFilter extends OncePerRequestFilter {

    private static final byte[] REJECTION = ("{\"error\": \"跨站请求被拒绝\"}")
        .getBytes(StandardCharsets.UTF_8);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean isPost = "POST".equalsIgnoreCase(request.getMethod());
        boolean isLogin = "/api/auth/login".equals(request.getRequestURI());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
            && !(authentication instanceof org.springframework.security.authentication
                .AnonymousAuthenticationToken);
        if (isPost && !isLogin && authenticated && !SameOrigin.isSameOrigin(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json; charset=utf-8");
            response.setContentLength(REJECTION.length);
            response.getOutputStream().write(REJECTION);
            return;
        }
        chain.doFilter(request, response);
    }
}
