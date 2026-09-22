package io.github.lxyang01.billguard.storage;

import io.github.lxyang01.billguard.auth.AuthError;
import io.github.lxyang01.billguard.auth.Pbkdf2;
import io.github.lxyang01.billguard.auth.User;
import io.github.lxyang01.billguard.auth.UserStore;
import io.github.lxyang01.billguard.coordination.RedisAuthSessions;
import io.github.lxyang01.agent.types.Timestamps;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PG 用户库(users 表)。PBKDF2 校验带 dummy 哈希计时抹平;
 * 保护「最后一个启用中的管理员」不可降级/删除/禁用;
 * 改密/删户经 RedisAuthSessions 反向索引联动失效全部登录令牌。
 */
public final class PgUserStore implements UserStore {

    public static final Set<String> ROLES = Set.of("admin", "user");
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,31}$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> new User(
        rs.getString("username"), rs.getString("role"), rs.getBoolean("disabled"),
        rs.getString("created_at"));

    private final JdbcTemplate jdbc;
    private final RedisAuthSessions sessions;
    private final SecureRandom random = new SecureRandom();

    public PgUserStore(JdbcTemplate jdbc, RedisAuthSessions sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    public User create(String username, String password, String role) {
        String name = username == null ? "" : username.strip();
        validateUsername(name);
        validatePassword(password);
        if (!ROLES.contains(role)) {
            throw new AuthError("角色必须是 ('admin', 'user') 之一");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        try {
            jdbc.update(
                "INSERT INTO users(username, password_hash, salt, role, disabled, created_at) "
                    + "VALUES (?, ?, ?, ?, FALSE, ?)",
                name, Pbkdf2.hex(Pbkdf2.hash(password, salt)), Pbkdf2.hex(salt), role,
                Timestamps.nowIso());
        } catch (DuplicateKeyException e) {
            throw new AuthError("用户已存在：" + name);
        }
        return get(name);
    }

    @Override
    public User get(String username) {
        List<User> found = jdbc.query(
            "SELECT username, role, disabled, created_at FROM users WHERE username = ?",
            ROW_MAPPER, username);
        if (found.isEmpty()) {
            throw new AuthError("用户不存在：" + username);
        }
        return found.get(0);
    }

    @Override
    public List<User> list() {
        return jdbc.query(
            "SELECT username, role, disabled, created_at FROM users ORDER BY username",
            ROW_MAPPER);
    }

    @Override
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public User verify(String username, String password) {
        List<Credential> found = jdbc.query(
            "SELECT username, role, disabled, created_at, password_hash, salt FROM users "
                + "WHERE username = ?",
            (rs, rowNum) -> new Credential(rs.getString("username"), rs.getString("role"),
                rs.getBoolean("disabled"), rs.getString("created_at"),
                rs.getString("password_hash"), rs.getString("salt")),
            username == null ? "" : username.strip());
        // 统一失败信息,不区分「用户不存在」与「密码错误」;
        // 未知用户名也执行一次等代价哈希,抹平计时差
        if (found.isEmpty()) {
            Pbkdf2.constantTimeEquals(Pbkdf2.hex(Pbkdf2.hash(password, Pbkdf2.DUMMY_SALT)),
                Pbkdf2.DUMMY_HASH);
            throw new AuthError("用户名或密码错误");
        }
        Credential credential = found.get(0);
        String candidate = Pbkdf2.hex(Pbkdf2.hash(password,
            java.util.HexFormat.of().parseHex(credential.salt())));
        if (!Pbkdf2.constantTimeEquals(candidate, credential.passwordHash())) {
            throw new AuthError("用户名或密码错误");
        }
        if (credential.disabled()) {
            throw new AuthError("账号已被禁用");
        }
        return new User(credential.username(), credential.role(), credential.disabled(),
            credential.createdAt());
    }

    @Override
    public User setRole(String username, String role) {
        if (!ROLES.contains(role)) {
            throw new AuthError("角色必须是 ('admin', 'user') 之一");
        }
        User current = get(username);
        if ("admin".equals(current.role()) && !"admin".equals(role) && !current.disabled()
            && enabledAdminsExcluding(username) == 0) {
            throw new AuthError("不能降级最后一个启用中的管理员");
        }
        jdbc.update("UPDATE users SET role = ? WHERE username = ?", role, username);
        return get(username);
    }

    @Override
    public void resetPassword(String username, String password) {
        validatePassword(password);
        get(username);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        jdbc.update("UPDATE users SET password_hash = ?, salt = ? WHERE username = ?",
            Pbkdf2.hex(Pbkdf2.hash(password, salt)), Pbkdf2.hex(salt), username);
        // 旧凭证对应的存量会话一并失效
        sessions.deleteByUser(username);
    }

    @Override
    public void delete(String username) {
        User current = get(username);
        if ("admin".equals(current.role()) && !current.disabled()
            && enabledAdminsExcluding(username) == 0) {
            throw new AuthError("不能删除最后一个启用中的管理员");
        }
        jdbc.update("DELETE FROM users WHERE username = ?", username);
        sessions.deleteByUser(username);
    }

    @Override
    public User setDisabled(String username, boolean disabled) {
        User current = get(username);
        if ("admin".equals(current.role()) && disabled && !current.disabled()
            && enabledAdminsExcluding(username) == 0) {
            throw new AuthError("不能禁用最后一个启用中的管理员");
        }
        jdbc.update("UPDATE users SET disabled = ? WHERE username = ?", disabled, username);
        return get(username);
    }

    private int enabledAdminsExcluding(String username) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'admin' AND disabled = FALSE "
                + "AND username != ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    private static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new AuthError("用户名须为 2-32 位小写字母/数字，可用 - _ 连接");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AuthError("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private record Credential(String username, String role, boolean disabled, String createdAt,
                              String passwordHash, String salt) {}
}
