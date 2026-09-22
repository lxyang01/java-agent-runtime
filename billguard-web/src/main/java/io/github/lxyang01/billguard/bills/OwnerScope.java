package io.github.lxyang01.billguard.bills;

import java.util.ArrayList;
import java.util.List;

/**
 * 多租户 owner 子句三态(对齐 bills.py _owner_clause):
 * ""(服务端未注入身份)→ 仅存量 NULL 行;"admin" → 本人 + NULL 存量(与 Session 归属同构);
 * 其他用户 → 仅本人行。null(根服务)不加子句。
 */
public final class OwnerScope {

    private OwnerScope() {}

    public record Clause(String sql, List<Object> params) {}

    public static Clause clause(String owner, String column) {
        if (owner == null) {
            return new Clause("", new ArrayList<>());
        }
        if (owner.isEmpty()) {
            return new Clause(column + " IS NULL", new ArrayList<>());
        }
        if ("admin".equals(owner)) {
            return new Clause("(" + column + " = ? OR " + column + " IS NULL)",
                new ArrayList<>(List.of(owner)));
        }
        return new Clause(column + " = ?", new ArrayList<>(List.of(owner)));
    }

    /** 独立 WHERE(" WHERE ...");owner=null 时为空串。 */
    public static Clause where(String owner, String column) {
        Clause clause = clause(owner, column);
        if (clause.sql().isEmpty()) {
            return clause;
        }
        return new Clause(" WHERE " + clause.sql(), clause.params());
    }

    /** 拼到既有 WHERE 末尾(" AND ...");owner=null 时为空串。 */
    public static Clause and(String owner, String column) {
        Clause clause = clause(owner, column);
        if (clause.sql().isEmpty()) {
            return clause;
        }
        return new Clause(" AND " + clause.sql(), clause.params());
    }
}
