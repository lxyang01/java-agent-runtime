package io.github.lxyang01.billguard.coordination;

import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 登录会话:token 只以 sha256 摘要落键名,值存 username。
 * 反向索引 auth:user:{username}(SET)让改密/删户整批失效;索引 TTL 与令牌对齐
 * 且随滑动续期同步续期 —— 索引不会先于任何活跃令牌过期。对齐 coordination.py。
 */
public final class RedisAuthSessions {

    public static final long SESSION_TTL_SECONDS = TimeUnit.DAYS.toSeconds(7);
    private static final long REFRESH_THRESHOLD_SECONDS = TimeUnit.DAYS.toSeconds(6);

    private final RedisCommands<String, String> commands;
    private final SecureRandom random = new SecureRandom();

    public RedisAuthSessions(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(value.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String tokenKey(String token) {
        return "auth:token:" + sha256Hex(token);
    }

    private static String userIndexKey(String username) {
        return "auth:user:" + username;
    }

    public String create(String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String key = tokenKey(token);
        commands.set(key, username, io.lettuce.core.SetArgs.Builder.ex(SESSION_TTL_SECONDS));
        String index = userIndexKey(username);
        commands.sadd(index, key);
        commands.expire(index, SESSION_TTL_SECONDS);
        return token;
    }

    /** 校验并按阈值滑动续期;无效返回 null。 */
    public String resolve(String token) {
        String key = tokenKey(token);
        String username = commands.get(key);
        if (username == null) {
            return null;
        }
        long ttl = commands.ttl(key);
        if (ttl > 0 && ttl < REFRESH_THRESHOLD_SECONDS) {
            commands.expire(key, SESSION_TTL_SECONDS);
            // 令牌仍活跃:反向索引同步续期,不得先于令牌过期
            commands.expire(userIndexKey(username), SESSION_TTL_SECONDS);
        }
        return username;
    }

    public void delete(String token) {
        String key = tokenKey(token);
        String username = commands.get(key);
        commands.del(key);
        if (username != null) {
            // 索引同步摘除;SET 弹空时 Redis 自动删键,登出零残留
            commands.srem(userIndexKey(username), key);
        }
    }

    /** 失效该用户全部登录令牌(改密/删户联动),返回清除的令牌数。 */
    public int deleteByUser(String username) {
        String index = userIndexKey(username);
        List<String> keys = commands.smembers(index).stream().toList();
        if (!keys.isEmpty()) {
            commands.del(keys.toArray(new String[0]));
        }
        commands.del(index);
        return keys.size();
    }
}
