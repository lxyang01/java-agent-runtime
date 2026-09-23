package io.github.lxyang01.billguard.security;

import io.github.lxyang01.billguard.auth.AuthError;
import io.github.lxyang01.billguard.auth.Authenticator;
import io.github.lxyang01.billguard.auth.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 令牌认证:Cookie session= → Redis 校验 → SecurityContext(角色权限 ROLE_admin/ROLE_user)。
 * 认证失败不在此拒绝 —— 具体错误消息(未登录/过期)存请求属性,由入口点统一发 401,
 * 保持与 「401 先于 CSRF 403」的顺序语义。
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "billguard.authError";
    public static final String USER_ATTRIBUTE = "billguard.user";

    private final Authenticator authenticator;

    public TokenAuthFilter(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = Authenticator.tokenFromCookie(request.getHeader("Cookie"));
        if (token != null) {
            try {
                User user = authenticator.resolveUser(token);
                request.setAttribute(USER_ATTRIBUTE, user);
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                    user.username(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (AuthError e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
