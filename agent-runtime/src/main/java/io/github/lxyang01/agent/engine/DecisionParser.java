package io.github.lxyang01.agent.engine;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lxyang01.agent.util.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析模型单步输出为 {@link AgentDecision}。逐分支parser.parse_decision:
 * 围栏 JSON 优先 → 全文宽松解析(从第一个 '{' 起,忽略尾随噪声)→ 数组/函数形态/裸对象等分支。
 */
public final class DecisionParser {

    private static final Pattern FENCED =
        Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** 协议控制字段:命中任一即按协议对象处理,不再当"裸结果对象"。 */
    private static final Set<String> PROTOCOL_KEYS =
        Set.of("thought", "tool_call", "tool_calls", "final", "answer");

    private static final JsonFactory FACTORY = Json.MAPPER.getFactory();

    private DecisionParser() {}

    public static AgentDecision parse(String text) {
        String stripped = text.strip();
        Matcher fenced = FENCED.matcher(text);
        List<String> candidates;
        if (fenced.find()) {
            candidates = List.of(fenced.group(1), stripped);
        } else {
            candidates = List.of(stripped);
        }
        for (String candidate : candidates) {
            JsonNode value = tryParse(candidate);
            if (value == null) {
                continue;
            }
            // 数组:首元素含协议键 → 取第一个动作;纯结果数组 → 结构化 final;空数组 → 下一候选
            if (value.isArray()) {
                if (value.isEmpty() || !value.get(0).isObject()) {
                    continue;
                }
                if (!intersectsProtocolKeys(value.get(0))) {
                    return new FinalDecision("", Json.writePretty(value));
                }
                value = value.get(0);
            }
            if (!value.isObject()) {
                continue;
            }
            AgentDecision decision = parseObject(value);
            if (decision != null) {
                return decision;
            }
        }
        throw new DecisionParseException(
            "LLM output must contain exactly one valid tool_call or final answer");
    }

    private static AgentDecision parseObject(JsonNode value) {
        String thought = textOf(value, "thought");
        boolean hasFinal = value.has("final") || value.has("answer");
        JsonNode finalNode = value.has("final") ? value.get("final") : value.get("answer");

        JsonNode call = value.get("tool_call");
        if (call == null || call.isNull()) {
            JsonNode calls = value.get("tool_calls");
            if (calls != null && calls.isArray() && !calls.isEmpty()) {
                call = calls.get(0);
            }
        }
        if (call != null && call.isObject() && call.get("function") != null
            && call.get("function").isObject()) {
            call = call.get("function");
        }
        if (call != null && call.isObject() && call.get("name") != null
            && call.get("name").isTextual()) {
            JsonNode arguments = call.get("arguments");
            if (arguments == null || arguments.isNull()) {
                arguments = Json.MAPPER.createObjectNode();
            }
            if (arguments.isTextual()) {
                try {
                    arguments = Json.readTree(arguments.asText());
                } catch (RuntimeException e) {
                    arguments = null;
                }
            }
            if (arguments != null && arguments.isObject()) {
                // 工具动作优先于同时出现的 premature final
                return new ToolCallDecision(thought, call.get("name").asText(), toMap(arguments));
            }
        }
        if (hasFinal && finalNode != null && !finalNode.isNull()) {
            String answer = finalNode.isTextual() ? finalNode.asText() : Json.writePretty(finalNode);
            return new FinalDecision(thought, answer);
        }
        // JSON-only 模式有时按请求结构返回但漏掉 {"final": ...} 外壳:
        // 不含任何协议控制字段的非空对象按结构化 final 处理
        if (!value.isEmpty() && !intersectsProtocolKeys(value)) {
            return new FinalDecision(thought, Json.writePretty(value));
        }
        return null;
    }

    private static boolean intersectsProtocolKeys(JsonNode object) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            if (PROTOCOL_KEYS.contains(fields.next())) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.asText();
    }

    private static Map<String, Object> toMap(JsonNode node) {
        return Json.MAPPER.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<
            LinkedHashMap<String, Object>>() {});
    }

    /** 先整段解析;失败则从第一个 '{' 起宽松解析(忽略尾随内容,对齐 raw_decode)。 */
    private static JsonNode tryParse(String candidate) {
        try {
            return Json.MAPPER.readTree(candidate);
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        int start = candidate.indexOf('{');
        if (start < 0) {
            return null;
        }
        try (JsonParser parser = FACTORY.createParser(candidate.substring(start))) {
            if (parser.nextToken() == null) {
                return null;
            }
            return Json.MAPPER.readTree(parser);
        } catch (Exception e) {
            return null;
        }
    }
}
