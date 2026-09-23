package io.github.lxyang01.billguard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthenticatorTest {

    @Test
    void capability_matrix_matches_role_model() {
        assertThat(Capabilities.can("user", "bills_write")).isTrue();
        assertThat(Capabilities.can("user", "report_write")).isTrue();
        assertThat(Capabilities.can("user", "approval_decide")).isTrue();
        assertThat(Capabilities.can("user", "users_manage")).isFalse();
        assertThat(Capabilities.can("admin", "users_manage")).isTrue();
        assertThat(Capabilities.can("ghost", "bills_write")).isFalse();
    }

    @Test
    void cookie_strings_exact() {
        var cookies = new SessionCookies("");
        assertThat(cookies.login("tok123"))
            .isEqualTo("session=tok123; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800");
        assertThat(cookies.clear())
            .isEqualTo("session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");

        var secure = new SessionCookies("true");
        assertThat(secure.login("t")).endsWith("; Secure");
        assertThat(secure.clear()).endsWith("; Secure");
    }

    @Test
    void token_extraction_from_cookie_header() {
        assertThat(Authenticator.tokenFromCookie("session=abc; other=1")).isEqualTo("abc");
        assertThat(Authenticator.tokenFromCookie("other=1; session=xyz")).isEqualTo("xyz");
        assertThat(Authenticator.tokenFromCookie("session=")).isNull();
        assertThat(Authenticator.tokenFromCookie(null)).isNull();
        assertThat(Authenticator.tokenFromCookie("nosession=1")).isNull();
    }
}
