package io.github.lxyang01.billguard.web;

import io.github.lxyang01.billguard.auth.AuthError;
import io.github.lxyang01.billguard.auth.Authenticator;
import io.github.lxyang01.billguard.auth.Capabilities;
import io.github.lxyang01.billguard.auth.PermissionDenied;
import io.github.lxyang01.billguard.auth.SessionCookies;
import io.github.lxyang01.billguard.auth.User;
import io.github.lxyang01.billguard.auth.UserStore;
import io.github.lxyang01.billguard.coordination.RedisLoginThrottle;
import io.github.lxyang01.billguard.security.SameOrigin;
import io.github.lxyang01.billguard.security.TokenAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 认证与用户管理端点(顺序语义逐字对齐 web.py 登录分支)。 */
@RestController
public class AuthController {

    private final Authenticator authenticator;
    private final UserStore users;
    private final RedisLoginThrottle throttle;
    private final SessionCookies cookies;

    public AuthController(Authenticator authenticator, UserStore users,
                          RedisLoginThrottle throttle, SessionCookies cookies) {
        this.authenticator = authenticator;
        this.users = users;
        this.throttle = throttle;
        this.cookies = cookies;
    }

    /**
     * 登录(唯一免认证 POST)。顺序:参数校验(400)→ 同源校验(403,登录 CSRF
     * 是真实攻击类别)→ 节流(429)→ 凭据验证(401,计次)。空凭据校验在
     * 同源之前:同为非法请求时优先报参数错误,不给攻击者探测差异的信息。
     */
    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        String username = text(body.get("username"));
        String password = text(body.get("password"));
        if (username.isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        if (!SameOrigin.isSameOrigin(request)) {
            return jsonResponse(403, Map.of("error", "跨站请求被拒绝"), null);
        }
        String ip = clientIp(request);
        if (!throttle.allowed(username, ip)) {
            return jsonResponse(429, Map.of("error", "登录失败次数过多,请稍后再试"), null);
        }
        Authenticator.LoginResult result;
        try {
            result = authenticator.login(username, password);
        } catch (AuthError e) {
            throttle.recordFailure(username, ip);
            return jsonResponse(401, Map.of("error", e.getMessage()), null);
        }
        throttle.reset(username, ip);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", result.user().username());
        user.put("role", result.user().role());
        return jsonResponse(200, Map.of("user", user), cookies.login(result.token()));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        authenticator.logout(Authenticator.tokenFromCookie(request.getHeader("Cookie")));
        return jsonResponse(200, Map.of("ok", true), cookies.clear());
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(@RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.username());
        body.put("role", user.role());
        return body;
    }

    @GetMapping("/api/admin/users")
    public Map<String, Object> listUsers(
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        if (!Capabilities.can(user.role(), Capabilities.USERS_MANAGE)) {
            throw new PermissionDenied("仅管理员可管理用户");
        }
        List<Map<String, Object>> list = users.list().stream().map(u -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("username", u.username());
            item.put("role", u.role());
            item.put("disabled", u.disabled());
            item.put("created_at", u.createdAt());
            return item;
        }).toList();
        return Map.of("users", list);
    }

    @PostMapping("/api/admin/users")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        User created = users.create(text(body.get("username")), text(body.get("password")),
            text(body.get("role")));
        return Map.of("user", userView(created));
    }

    @PostMapping("/api/admin/users/role")
    public Map<String, Object> setRole(@RequestBody Map<String, Object> body) {
        User updated = users.setRole(text(body.get("username")), text(body.get("role")));
        return Map.of("user", userView(updated));
    }

    @PostMapping("/api/admin/users/password")
    public Map<String, Object> resetPassword(@RequestBody Map<String, Object> body) {
        users.resetPassword(text(body.get("username")), text(body.get("password")));
        return Map.of("ok", true);
    }

    @PostMapping("/api/admin/users/disable")
    public Map<String, Object> setDisabled(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User caller) {
        String username = text(body.get("username"));
        if (username.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (username.equals(caller.username())) {
            throw new IllegalArgumentException("不能禁用当前登录的账号");
        }
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        return Map.of("user", userView(users.setDisabled(username, disabled)));
    }

    @PostMapping("/api/admin/users/delete")
    public Map<String, Object> deleteUser(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User caller) {
        String username = text(body.get("username"));
        if (username.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (username.equals(caller.username())) {
            throw new IllegalArgumentException("不能删除当前登录的账号");
        }
        users.delete(username);
        return Map.of("deleted", true, "username", username);
    }

    private static Map<String, Object> userView(User user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("username", user.username());
        view.put("role", user.role());
        view.put("disabled", user.disabled());
        return view;
    }

    /** 集群内全部流量经 nginx(X-Real-IP 强制覆写);本地开发回退远端地址。 */
    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static ResponseEntity<Map<String, Object>> jsonResponse(
        int status, Map<String, Object> body, String setCookie) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (setCookie != null) {
            builder.header("Set-Cookie", setCookie);
        }
        return builder.body(body);
    }
}
