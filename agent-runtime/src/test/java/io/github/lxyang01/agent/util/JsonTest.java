package io.github.lxyang01.agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void write_keeps_non_ascii_verbatim() {
        // 非 ASCII 不转义
        assertThat(Json.write(Map.of("k", "中文"))).isEqualTo("{\"k\":\"中文\"}");
    }

    @Test
    void write_preserves_insertion_order() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("b", 1);
        ordered.put("a", 2);
        assertThat(Json.write(ordered)).isEqualTo("{\"b\":1,\"a\":2}");
    }

    @Test
    void writePretty_indents() {
        assertThat(Json.writePretty(Map.of("a", 1))).contains("\n");
    }

    @Test
    void readTree_is_lenient_about_unknown_fields() {
        // 会话消息 JSONB 含未知字段时不得整体失败
        var node = Json.readTree("{\"role\":\"user\",\"content\":\"hi\",\"extra\":1}");
        assertThat(node.path("content").asText()).isEqualTo("hi");
    }
}
