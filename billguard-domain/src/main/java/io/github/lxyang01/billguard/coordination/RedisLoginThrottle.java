package io.github.lxyang01.billguard.coordination;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 登录暴力破解防护:按 (username, ip) 失败计数,达 maxFailures 次锁定。
 * 固定窗口从第一次失败起算,不滑动续期;Lua 保证 INCR 与首次 EXPIRE 原子。
 * 键 login:fail:{sha256(username|ip)};窗口过期整键消失即自动解锁;成功登录清零。
 */
public final class RedisLoginThrottle {

    private static final String LOGIN_FAIL_LUA = """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return current""";

    private final RedisCommands<String, String> commands;
    private final int maxFailures;
    private final int windowSeconds;

    public RedisLoginThrottle(RedisCommands<String, String> commands) {
        this(commands, 5, 600);
    }

    public RedisLoginThrottle(RedisCommands<String, String> commands, int maxFailures,
                              int windowSeconds) {
        this.commands = commands;
        this.maxFailures = maxFailures;
        this.windowSeconds = windowSeconds;
    }

    private String key(String username, String ip) {
        String material = username + "|" + ip;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(material.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return "login:fail:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean allowed(String username, String ip) {
        String count = commands.get(key(username, ip));
        return count == null || Integer.parseInt(count) < maxFailures;
    }

    public void recordFailure(String username, String ip) {
        commands.eval(LOGIN_FAIL_LUA, ScriptOutputType.INTEGER,
            new String[]{key(username, ip)}, String.valueOf(windowSeconds));
    }

    public void reset(String username, String ip) {
        commands.del(key(username, ip));
    }
}
