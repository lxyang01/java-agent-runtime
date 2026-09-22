package io.github.lxyang01.billguard.auth;

import io.github.lxyang01.billguard.coordination.RedisAuthSessions;

/**
 * 唯一身份边界(换 SSO 时替换此层):login/logout/resolveUser。
 * Cookie 解析在此层完成;用户被删则 get 失败,禁用则拒。
 */
public final class Authenticator {

    private final UserStore users;
    private final RedisAuthSessions sessions;

    public Authenticator(UserStore users, RedisAuthSessions sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    public record LoginResult(User user, String token) {}

    public LoginResult login(String username, String password) {
        User user = users.verify(username, password);
        return new LoginResult(user, sessions.create(user.username()));
    }

    public void logout(String token) {
        if (token != null && !token.isEmpty()) {
            sessions.delete(token);
        }
    }

    public User resolveUser(String token) {
        if (token == null || token.isEmpty()) {
            throw new AuthError("未登录或会话已失效");
        }
        String username = sessions.resolve(token);
        if (username == null) {
            throw new AuthError("登录已过期,请重新登录");
        }
        User user = users.get(username);
        if (user.disabled()) {
            throw new AuthError("账号已被禁用");
        }
        return user;
    }

    /** 从 Cookie 头解析 session 值;缺失返回 null。 */
    public static String tokenFromCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.strip();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && SessionCookies.NAME.equals(trimmed.substring(0, eq))
                && trimmed.length() > eq + 1) {
                return trimmed.substring(eq + 1);
            }
        }
        return null;
    }
}
