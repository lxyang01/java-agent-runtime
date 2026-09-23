package io.github.lxyang01.billguard.auth;

/** 用户(角色 admin/user;disabled 由登录与访问层拒绝)。 */
public record User(String username, String role, boolean disabled, String createdAt) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
