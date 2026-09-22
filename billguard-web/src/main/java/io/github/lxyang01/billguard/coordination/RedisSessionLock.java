package io.github.lxyang01.billguard.coordination;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis 分布式会话锁。键名与 Lua 逐字对齐 Python coordination.py:
 * SET NX PX 抢锁;释放走持有者校验 Lua,只删自己的锁。
 */
public final class RedisSessionLock {

    /** 仅当锁仍归自己持有时删除(防误删他人锁)。 */
    private static final String RELEASE_LUA = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0""";

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

    private final RedisCommands<String, String> commands;
    private final String key;
    private final String holder;

    private RedisSessionLock(RedisCommands<String, String> commands, String key,
                             String holder) {
        this.commands = commands;
        this.key = key;
        this.holder = holder;
    }

    /** 抢锁:成功返回已持有的锁;被占用返回空(语义=立即 423,不排队)。 */
    public static Optional<RedisSessionLock> acquire(RedisCommands<String, String> commands,
                                                     String sessionId, Duration ttl) {
        String key = "lock:session:" + sha256Hex(sessionId);
        String holder = UUID.randomUUID().toString().replace("-", "");
        String ok = commands.set(key, holder, SetArgs.Builder.nx().px(ttl.toMillis()));
        return "OK".equals(ok) ? Optional.of(new RedisSessionLock(commands, key, holder))
            : Optional.empty();
    }

    public void release() {
        commands.eval(RELEASE_LUA, ScriptOutputType.INTEGER, new String[]{key}, holder);
    }
}
