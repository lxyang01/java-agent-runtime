package io.github.lxyang01.billguard.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.github.lxyang01.billguard.PgTestBase;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisCoordinationTest extends PgTestBase {

    RedisClient redisClient;
    RedisCommands<String, String> commands;

    @BeforeAll
    void connectRedis() {
        redisClient = RedisClient.create(redisUri());
        commands = redisClient.connect().sync();
        // 测试键清场(与 sweep_redis 同义)
        commands.flushdb();
    }

    @AfterAll
    void closeRedis() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void lock_acquire_then_second_fails_until_release() {
        var first = RedisSessionLock.acquire(commands, "session-A", Duration.ofSeconds(30));
        assertThat(first).isPresent();
        assertThat(RedisSessionLock.acquire(commands, "session-A", Duration.ofSeconds(30)))
            .isEmpty();
        first.get().release();
        assertThat(RedisSessionLock.acquire(commands, "session-A", Duration.ofSeconds(30)))
            .isPresent();
    }

    @Test
    void release_only_deletes_own_lock() throws InterruptedException {
        var first = RedisSessionLock.acquire(commands, "session-B", Duration.ofMillis(200));
        assertThat(first).isPresent();
        Thread.sleep(300);   // 第一把锁 TTL 过期
        var second = RedisSessionLock.acquire(commands, "session-B", Duration.ofSeconds(30));
        assertThat(second).isPresent();
        first.get().release();   // 过期持有者释放,不得误删第二把
        assertThat(RedisSessionLock.acquire(commands, "session-B", Duration.ofSeconds(30)))
            .isEmpty();          // 第二把仍在
        second.get().release();
    }

    @Test
    void limiter_enforces_limit_and_floors() {
        var limiter = new RedisLlmLimiter(commands, 2, "llm:slots:test");
        commands.del("llm:slots:test");
        assertThat(limiter.acquire()).isTrue();
        assertThat(limiter.acquire()).isTrue();
        assertThat(limiter.acquire()).isFalse();   // 满额
        limiter.release();
        assertThat(limiter.acquire()).isTrue();
        limiter.release();
        limiter.release();   // 多释放不落负
        limiter.release();
        String value = commands.get("llm:slots:test");
        assertThat(value == null || Integer.parseInt(value) >= 0).isTrue();
        assertThat(Integer.parseInt(commands.get("llm:slots:test"))).isGreaterThanOrEqualTo(0);
    }
}
