package io.github.lxyang01.billguard.auth;

/** 已认证但无权执行该操作/访问该会话(403)。 */
public class PermissionDenied extends RuntimeException {

    public PermissionDenied(String message) {
        super(message);
    }
}
