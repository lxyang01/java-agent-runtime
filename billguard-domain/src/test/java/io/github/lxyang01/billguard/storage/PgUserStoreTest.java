package io.github.lxyang01.billguard.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.auth.AuthError;
import io.github.lxyang01.billguard.auth.User;
import io.github.lxyang01.billguard.coordination.RedisAuthSessions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgUserStoreTest extends PgTestBase {

    RedisClient redisClient;
    RedisCommands<String, String> redis;
    PgUserStore store;

    @BeforeAll
    void initStore() {
        redisClient = RedisClient.create(redisUri());
        redis = redisClient.connect().sync();
        redis.flushdb();
        store = new PgUserStore(jdbc, new RedisAuthSessions(redis));
    }

    @AfterAll
    void closeRedis() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void create_and_verify_roundtrip() {
        store.create("alice", "password-1", "user");
        User verified = store.verify("alice", "password-1");
        assertThat(verified.username()).isEqualTo("alice");
        assertThat(verified.role()).isEqualTo("user");
        assertThat(verified.disabled()).isFalse();
    }

    @Test
    void wrong_password_and_unknown_user_share_message() {
        store.create("bob", "password-2", "user");
        assertThatThrownBy(() -> store.verify("bob", "wrong-pass"))
            .isInstanceOf(AuthError.class).hasMessage("用户名或密码错误");
        assertThatThrownBy(() -> store.verify("ghost", "whatever-1"))
            .isInstanceOf(AuthError.class).hasMessage("用户名或密码错误");
    }

    @Test
    void disabled_account_rejected_after_credentials() {
        store.create("carol", "password-3", "user");
        store.setDisabled("carol", true);
        assertThatThrownBy(() -> store.verify("carol", "password-3"))
            .isInstanceOf(AuthError.class).hasMessage("账号已被禁用");
    }

    @Test
    void duplicate_user_rejected() {
        store.create("dave", "password-4", "user");
        assertThatThrownBy(() -> store.create("dave", "password-4", "user"))
            .isInstanceOf(AuthError.class).hasMessage("用户已存在：dave");
    }

    @Test
    void username_and_password_rules_enforced() {
        assertThatThrownBy(() -> store.create("Bad Name", "password-5", "user"))
            .hasMessage("用户名须为 2-32 位小写字母/数字，可用 - _ 连接");
        assertThatThrownBy(() -> store.create("okname", "short", "user"))
            .hasMessage("密码至少 8 位");
        assertThatThrownBy(() -> store.create("okname2", "password-6", "root"))
            .hasMessage("角色必须是 ('admin', 'user') 之一");
    }

    @Test
    void last_enabled_admin_protected() {
        store.create("root-a", "password-7", "admin");
        assertThatThrownBy(() -> store.setRole("root-a", "user"))
            .hasMessage("不能降级最后一个启用中的管理员");
        assertThatThrownBy(() -> store.delete("root-a"))
            .hasMessage("不能删除最后一个启用中的管理员");
        assertThatThrownBy(() -> store.setDisabled("root-a", true))
            .hasMessage("不能禁用最后一个启用中的管理员");
        // 第二个管理员到位后即可降级其一
        store.create("root-b", "password-8", "admin");
        assertThat(store.setRole("root-a", "user").role()).isEqualTo("user");
        // root-b 成为最后一个 → 保护生效
        assertThatThrownBy(() -> store.delete("root-b"))
            .hasMessage("不能删除最后一个启用中的管理员");
    }

    @Test
    void reset_password_invalidates_existing_tokens() {
        var sessions = new RedisAuthSessions(redis);
        store.create("erin", "password-9", "user");
        String token = sessions.create("erin");
        assertThat(sessions.resolve(token)).isEqualTo("erin");
        store.resetPassword("erin", "password-10");
        assertThat(sessions.resolve(token)).isNull();       // 旧令牌全部失效
        assertThat(store.verify("erin", "password-10").username()).isEqualTo("erin");
    }

    @Test
    void delete_user_invalidates_tokens() {
        var sessions = new RedisAuthSessions(redis);
        store.create("frank", "password-11", "user");
        String token = sessions.create("frank");
        store.delete("frank");
        assertThat(sessions.resolve(token)).isNull();
        assertThatThrownBy(() -> store.get("frank")).hasMessage("用户不存在：frank");
    }
}
