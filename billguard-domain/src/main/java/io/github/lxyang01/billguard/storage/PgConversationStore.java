package io.github.lxyang01.billguard.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.lxyang01.agent.store.ConversationStore;
import io.github.lxyang01.agent.types.ChatMessage;
import io.github.lxyang01.agent.types.Conversation;
import io.github.lxyang01.agent.types.Timestamps;
import io.github.lxyang01.agent.util.Json;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** PG 会话存储:sessions 表 upsert,messages JSONB;SQL 对齐 Python PGSessionStore。 */
public final class PgConversationStore implements ConversationStore {

    private static final RowMapper<Conversation> ROW_MAPPER = (rs, rowNum) -> {
        List<ChatMessage> messages = Json.MAPPER.convertValue(
            readJsonb(rs.getObject("messages"), "[]"),
            new TypeReference<List<ChatMessage>>() {});
        return new Conversation(rs.getString("session_id"),
            rs.getString("summary") == null ? "" : rs.getString("summary"),
            rs.getString("owner"), messages);
    };

    private final JdbcTemplate jdbc;

    public PgConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 非法会话 id 拒绝(空/超长);与 Python _key 的校验副作用一致。 */
    static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()
            || sessionId.codePointCount(0, sessionId.length()) > 200) {
            throw new IllegalArgumentException("invalid session id");
        }
    }

    @Override
    public Conversation load(String sessionId) {
        validateSessionId(sessionId);
        List<Conversation> found = jdbc.query(
            "SELECT session_id, summary, owner, messages FROM sessions WHERE session_id = ?",
            ROW_MAPPER, sessionId);
        if (found.isEmpty()) {
            return Conversation.newConversation(sessionId);
        }
        return found.get(0);
    }

    @Override
    public void save(Conversation conversation) {
        validateSessionId(conversation.getSessionId());
        jdbc.update("""
            INSERT INTO sessions(session_id, owner, summary, messages, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (session_id) DO UPDATE SET
                owner = EXCLUDED.owner, summary = EXCLUDED.summary,
                messages = EXCLUDED.messages, updated_at = EXCLUDED.updated_at
            """,
            conversation.getSessionId(), conversation.getOwner(), conversation.getSummary(),
            PgJson.value(conversation.messages()), Timestamps.nowIso());
    }

    public boolean exists(String sessionId) {
        validateSessionId(sessionId);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE session_id = ?", Integer.class, sessionId);
        return count != null && count > 0;
    }

    public record SessionSummary(String sessionId, String owner, List<ChatMessage> messages,
                                 String updatedAt) {}

    /** 全量会话摘要(web 侧栏列表用;文件目录是各实例本地的,列表必须来自 PG)。 */
    public List<SessionSummary> list() {
        return jdbc.query("SELECT session_id, owner, messages, updated_at FROM sessions "
                + "ORDER BY updated_at DESC",
            (rs, rowNum) -> new SessionSummary(rs.getString("session_id"), rs.getString("owner"),
                Json.MAPPER.convertValue(readJsonb(rs.getObject("messages"), "[]"),
                    new TypeReference<List<ChatMessage>>() {}),
                rs.getString("updated_at")));
    }

    public int delete(String sessionId) {
        validateSessionId(sessionId);
        return jdbc.update("DELETE FROM sessions WHERE session_id = ?", sessionId);
    }

    private static com.fasterxml.jackson.databind.JsonNode readJsonb(Object value, String fallback) {
        if (value == null) {
            return Json.readTree(fallback);
        }
        return Json.readTree(String.valueOf(value));
    }
}
