package io.github.lxyang01.billguard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lxyang01.billguard.auth.Authenticator;
import io.github.lxyang01.billguard.auth.Capabilities;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全装配:STATELESS(不建 HttpSession)、关默认 CSRF(同源校验是唯一 CSRF 防线)、
 * TokenAuthFilter → SameOriginCsrfFilter → 路径能力门禁 → 授权。
 * JSON 错误体形状 {"error": "..."} 与 Python 一致。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final Authenticator authenticator;
    private final ObjectMapper mapper = new ObjectMapper();

    public SecurityConfig(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                // 16 条能力门禁路径(仅 POST;GET /api/admin/users 由控制器拒绝)
                .requestMatchers(HttpMethod.POST,
                    PathCapabilities.BY_PATH.keySet().toArray(new String[0]))
                .access(capabilityManager())
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())   // 静态资源(前端三件)无需登录
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler((request, response, ex) ->
                    writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        "当前角色无权执行此操作")))
            .addFilterBefore(new TokenAuthFilter(authenticator),
                UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new SameOriginCsrfFilter(),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 401:具体消息来自 TokenAuthFilter 存的属性(未登录/过期),缺省未登录。 */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            Object message = request.getAttribute(TokenAuthFilter.AUTH_ERROR_ATTRIBUTE);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                message != null ? String.valueOf(message) : "未登录或会话已失效");
        };
    }

    private AuthorizationManager<RequestAuthorizationContext> capabilityManager() {
        return (authentication, context) -> {
            HttpServletRequest request = context.getRequest();
            String capability = PathCapabilities.BY_PATH.get(request.getRequestURI());
            if (capability == null) {
                return new AuthorizationDecision(true);
            }
            Collection<? extends org.springframework.security.core.GrantedAuthority> authorities =
                authentication != null
                    ? authentication.get().getAuthorities() : java.util.List.of();
            boolean allowed = authorities.stream()
                .anyMatch(a -> a.getAuthority().startsWith("ROLE_")
                    && Capabilities.can(a.getAuthority().substring(5), capability));
            return new AuthorizationDecision(allowed);
        };
    }

    private void writeJson(HttpServletResponse response, int status, String message)
        throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        byte[] payload = mapper.writeValueAsBytes(body);
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
    }
}
