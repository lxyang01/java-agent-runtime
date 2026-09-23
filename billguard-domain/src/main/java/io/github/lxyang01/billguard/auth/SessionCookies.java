package io.github.lxyang01.billguard.auth;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 会话 Cookie 组装(HttpOnly; SameSite=Strict; Path=/; Max-Age=7d)。
 * BILLGUARD_SECURE_COOKIES∈{1,true} 时追加 Secure(仅 https 传输)。
 */
@Component
public class SessionCookies {

    public static final String NAME = "session";
    private static final long TTL_SECONDS = TimeUnit.DAYS.toSeconds(7);

    private final boolean secure;

    public SessionCookies(
        @Value("${BILLGUARD_SECURE_COOKIES:}") String secureFlag) {
        this.secure = Set.of("1", "true")
            .contains(secureFlag == null ? "" : secureFlag.strip().toLowerCase());
    }

    public String login(String token) {
        String cookie = NAME + "=" + token + "; HttpOnly; SameSite=Strict; Path=/; "
            + "Max-Age=" + TTL_SECONDS;
        return secure ? cookie + "; Secure" : cookie;
    }

    public String clear() {
        String cookie = NAME + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0";
        return secure ? cookie + "; Secure" : cookie;
    }
}
