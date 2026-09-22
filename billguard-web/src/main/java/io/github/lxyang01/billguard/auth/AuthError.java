package io.github.lxyang01.billguard.auth;

/** 认证失败(401):凭据缺失/错误/过期/账号禁用。 */
public class AuthError extends RuntimeException {

    public AuthError(String message) {
        super(message);
    }
}
