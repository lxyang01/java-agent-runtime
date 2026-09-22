package io.github.lxyang01.billguard.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lxyang01.billguard.PgTestBase;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisAuthTest extends PgTestBase {

    RedisClient redisClient;
    RedisCommands<String, String> commands;
    RedisAuthSessions sessions;
    RedisLoginThrottle throttle;

    @BeforeAll
    void connect() {
        redisClient = RedisClient.create(redisUri());
        commands = redisClient.connect().sync();
        commands.flushdb();
        sessions = new RedisAuthSessions(commands);
        throttle = new RedisLoginThrottle(commands);
    }

    @AfterAll
    void close() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void create_and_resolve_roundtrip_with_token_hash_keys() {
        String token = sessions.create("alice");
        assertThat(token).isNotBlank();
        // 明文 token 不落盘:键名是摘要
        assertThat(commands.keys("auth:token:*")).hasSize(1);
        assertThat(commands.get("auth:token:" + token)).isNull();
        assertThat(sessions.resolve(token)).isEqualTo("alice");
        // 反向索引存在
        assertThat(commands.smembers("auth:user:alice")).hasSize(1);
    }

    @Test
    void resolve_unknown_token_returns_null() {
        assertThat(sessions.resolve("no-such-token")).isNull();
    }

    @Test
    void delete_removes_token_and_index_entry() {
        String token = sessions.create("bob");
        sessions.delete(token);
        assertThat(sessions.resolve(token)).isNull();
        assertThat(commands.smembers("auth:user:bob")).isEmpty();
    }

    @Test
    void delete_by_user_invalidates_all_tokens() {
        String first = sessions.create("carol");
        String second = sessions.create("carol");
        assertThat(sessions.deleteByUser("carol")).isEqualTo(2);
        assertThat(sessions.resolve(first)).isNull();
        assertThat(sessions.resolve(second)).isNull();
    }

    @Test
    void sliding_refresh_extends_index_ttl() {
        String token = sessions.create("dave");
        String tokenKey = commands.keys("auth:token:*").stream()
            .filter(key -> "dave".equals(commands.get(key))).findFirst().orElseThrow();
        // 手动把 TTL 压到阈值(<6 天)之下,再 resolve 应续期
        commands.expire(tokenKey, TimeUnit.DAYS.toSeconds(1));
        assertThat(sessions.resolve(token)).isEqualTo("dave");
        assertThat(commands.ttl(tokenKey)).isGreaterThan(TimeUnit.DAYS.toSeconds(5));
        assertThat(commands.ttl("auth:user:dave")).isGreaterThan(TimeUnit.DAYS.toSeconds(5));
    }

    @Test
    void throttle_locks_after_max_failures_and_resets_on_success() {
        String user = "eve";
        String ip = "9.9.9.9";
        commands.del("login:fail:*");
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.allowed(user, ip)).isTrue();
            throttle.recordFailure(user, ip);
        }
        assertThat(throttle.allowed(user, ip)).isFalse();
        throttle.reset(user, ip);
        assertThat(throttle.allowed(user, ip)).isTrue();
    }

    @Test
    void throttle_keyed_by_username_and_ip_pair() {
        commands.del("login:fail:*");
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("frank", "1.1.1.1");
        }
        // 同名不同 IP / 同 IP 不同名都不受牵连
        assertThat(throttle.allowed("frank", "1.1.1.1")).isFalse();
        assertThat(throttle.allowed("frank", "2.2.2.2")).isTrue();
        assertThat(throttle.allowed("grace", "1.1.1.1")).isTrue();
    }
}
