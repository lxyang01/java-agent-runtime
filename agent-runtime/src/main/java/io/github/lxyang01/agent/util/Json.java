package io.github.lxyang01.agent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** 共享 ObjectMapper:非 ASCII 不转义(对齐 ensure_ascii=False);未知字段宽容。 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode readTree(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private Json() {}
}
