package io.github.lxyang01.billguard.auth;

import java.util.Map;
import java.util.Set;

/**
 * 角色能力表(内部标识,服务端与前端一致引用,不作文案展示)。
 * 能力与角色集合逐字。
 */
public final class Capabilities {

    public static final String REPORT_WRITE = "report_write";
    public static final String BILLS_WRITE = "bills_write";
    public static final String APPROVAL_DECIDE = "approval_decide";
    public static final String USERS_MANAGE = "users_manage";

    private static final Map<String, Set<String>> BY_ROLE = Map.of(
        "user", Set.of(REPORT_WRITE, BILLS_WRITE, APPROVAL_DECIDE),
        "admin", Set.of(REPORT_WRITE, BILLS_WRITE, APPROVAL_DECIDE, USERS_MANAGE));

    private Capabilities() {}

    public static boolean can(String role, String capability) {
        return BY_ROLE.getOrDefault(role, Set.of()).contains(capability);
    }
}
