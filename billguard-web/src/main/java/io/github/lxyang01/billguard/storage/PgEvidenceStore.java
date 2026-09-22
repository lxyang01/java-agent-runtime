package io.github.lxyang01.billguard.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.lxyang01.agent.util.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 证据存储:evidence 表按 (session_id, sha256(answer)) 主键 upsert,天然保留合并语义。 */
public final class PgEvidenceStore {

    private final JdbcTemplate jdbc;

    public PgEvidenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String answerKey(String answer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(answer.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Map<String, List<Map<String, Object>>> load(String sessionId) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        jdbc.query("SELECT answer_hash, evidence FROM evidence WHERE session_id = ?",
            rs -> {
                try {
                    result.put(rs.getString("answer_hash"),
                        Json.MAPPER.readValue(rs.getString("evidence"),
                            new TypeReference<List<Map<String, Object>>>() {}));
                } catch (Exception ignored) {
                    // 单行坏数据不拖垮整个加载
                }
            }, sessionId);
        return result;
    }

    public void save(String sessionId, String answer, List<Map<String, Object>> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return;
        }
        jdbc.update("INSERT INTO evidence(session_id, answer_hash, evidence) "
                + "VALUES (?, ?, ?) ON CONFLICT (session_id, answer_hash) "
                + "DO UPDATE SET evidence = EXCLUDED.evidence",
            sessionId, answerKey(answer), PgJson.value(evidence));
    }

    public int delete(String sessionId) {
        return jdbc.update("DELETE FROM evidence WHERE session_id = ?", sessionId);
    }
}
