package io.github.lxyang01.billguard.coordination;

import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.ScriptOutputType;

/**
 * 全集群 LLM 并发槽位:计数型(非轮询),check-and-incr Lua 原子,键 TTL 120s 自愈;
 * 释放走带地板的 DECR,不为负。脚本与键名逐字对齐 Python coordination.py。
 */
public final class RedisLlmLimiter {

    private static final String INCR_LUA = """
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        if current < tonumber(ARGV[1]) then
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
        end
        return 0""";

    private static final String FLOOR_DECR_LUA = """
        local v = tonumber(redis.call('GET', KEYS[1]) or '0')
        if v > 0 then return redis.call('DECR', KEYS[1]) end
        return 0""";

    private final RedisCommands<String, String> commands;
    private final int limit;
    private final String key;

    public RedisLlmLimiter(RedisCommands<String, String> commands, int limit) {
        this(commands, limit, "llm:slots");
    }

    public RedisLlmLimiter(RedisCommands<String, String> commands, int limit, String key) {
        this.commands = commands;
        this.limit = limit;
        this.key = key;
    }

    /** 取槽:全集群共享计数,超限返回 false(语义=429)。 */
    public boolean acquire() {
        Long result = commands.eval(INCR_LUA, ScriptOutputType.INTEGER, new String[]{key},
            String.valueOf(limit), "120");
        return result != null && result == 1;
    }

    public void release() {
        commands.eval(FLOOR_DECR_LUA, ScriptOutputType.INTEGER, new String[]{key});
    }
}
