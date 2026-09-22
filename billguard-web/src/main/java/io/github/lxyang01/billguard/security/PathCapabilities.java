package io.github.lxyang01.billguard.security;

import io.github.lxyang01.billguard.auth.Capabilities;
import java.util.Map;

/**
 * POST 路径 → 能力门禁表(逐字对齐 web.py _CAPABILITY_BY_PATH,16 条)。
 * GET /api/admin/users 不在此表 —— 由控制器以「仅管理员可管理用户」拒绝。
 */
public final class PathCapabilities {

    public static final Map<String, String> BY_PATH = Map.ofEntries(
        Map.entry("/api/reports/save", Capabilities.REPORT_WRITE),
        Map.entry("/api/reports/delete", Capabilities.REPORT_WRITE),
        Map.entry("/api/bills/import", Capabilities.BILLS_WRITE),
        Map.entry("/api/bills/categories", Capabilities.BILLS_WRITE),
        Map.entry("/api/category-rules/save", Capabilities.BILLS_WRITE),
        Map.entry("/api/category-rules/delete", Capabilities.BILLS_WRITE),
        Map.entry("/api/category-rules/rematch", Capabilities.BILLS_WRITE),
        Map.entry("/api/bills/workflow", Capabilities.BILLS_WRITE),
        Map.entry("/api/bills/purge", Capabilities.BILLS_WRITE),
        // 导出含未脱敏 note,按写级保护,所有登录用户均可导出
        Map.entry("/api/bills/export", Capabilities.BILLS_WRITE),
        Map.entry("/api/approvals/decide", Capabilities.APPROVAL_DECIDE),
        Map.entry("/api/admin/users", Capabilities.USERS_MANAGE),
        Map.entry("/api/admin/users/role", Capabilities.USERS_MANAGE),
        Map.entry("/api/admin/users/password", Capabilities.USERS_MANAGE),
        Map.entry("/api/admin/users/disable", Capabilities.USERS_MANAGE),
        Map.entry("/api/admin/users/delete", Capabilities.USERS_MANAGE));

    private PathCapabilities() {}
}
