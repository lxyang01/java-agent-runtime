package io.github.lxyang01.agent.guardrail;

import io.github.lxyang01.agent.util.Json;
import io.github.lxyang01.agent.util.Strings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入/输出护栏:长度上限、PII 脱敏、数字证据 grounding。
 * 逐字guardrails.py(模式、替换符、规整规则、6 位 HALF_EVEN 取整)。
 */
public final class Guardrails {

    public static final int MAX_USER_INPUT_CHARS = 32_000;
    public static final int MAX_MODEL_OUTPUT_CHARS = 64_000;

    private record PiiPattern(String name, Pattern pattern, String replacement) {}

    // order_id 模式必须 UNICODE_CHARACTER_CLASS:Unicode 词边界含中文("订单ORD…" 无边界 → 不脱敏),
    // Java 默认 ASCII \b 会误判 —— 该差异由测试 order_id_directly_after_chinese_is_not_boundary 锁定。
    private static final List<PiiPattern> PII_PATTERNS = List.of(
        new PiiPattern("phone", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[手机号]"),
        new PiiPattern("email",
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[邮箱]"),
        new PiiPattern("order_id",
            Pattern.compile("\\b(?:ORD|ORDER|NO)[-_]?[A-Za-z0-9-]{5,}\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS), "[订单号]"));

    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9_])[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern LIST_NUMBERING = Pattern.compile("(?m)^\\s*\\d+[.)、]\\s*");
    private static final Pattern SAMPLE_LABEL =
        Pattern.compile("(?i)(样本|反馈|案例|示例|sample|item)\\s*\\d+\\s*[:：.)、]");

    private Guardrails() {}

    public static void validateUserInput(String value) {
        if (Strings.len(value) > MAX_USER_INPUT_CHARS) {
            throw new GuardrailException(
                "user input exceeds the " + String.format("%,d", MAX_USER_INPUT_CHARS)
                    + " character limit");
        }
    }

    public static void validateModelOutput(String value) {
        if (Strings.len(value) > MAX_MODEL_OUTPUT_CHARS) {
            throw new GuardrailException(
                "model output exceeds the " + String.format("%,d", MAX_MODEL_OUTPUT_CHARS)
                    + " character limit");
        }
    }

    public static Redaction redactPii(String value) {
        String text = value;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PiiPattern pii : PII_PATTERNS) {
            Matcher matcher = pii.pattern().matcher(text);
            StringBuilder replaced = new StringBuilder();
            int count = 0;
            while (matcher.find()) {
                matcher.appendReplacement(replaced, Matcher.quoteReplacement(pii.replacement()));
                count++;
            }
            matcher.appendTail(replaced);
            text = replaced.toString();
            if (count > 0) {
                counts.put(pii.name(), count);
            }
        }
        return Redaction.of(text, counts);
    }

    /**
     * 返回最终回答中缺乏工具证据支撑的数字(升序)。
     * 用户原话不作为证据;工具参数算证据(证明运行时确实执行了该边界/区间),工具结果支撑业务指标。
     */
    public static List<Double> unsupportedNumericClaims(String answer, List<Object> evidenceValues) {
        Set<Double> claimed = numbers(answer);
        if (claimed.isEmpty()) {
            return List.of();
        }
        Set<Double> supported = new LinkedHashSet<>();
        for (Object value : evidenceValues) {
            supported.addAll(numbers(value));
        }
        List<Double> unsupported = new ArrayList<>();
        for (Double number : claimed) {
            if (!supported.contains(number)) {
                unsupported.add(number);
            }
        }
        return unsupported.stream().sorted().toList();
    }

    private static Set<Double> numbers(Object value) {
        String text = value instanceof String s ? s : Json.write(value);
        text = LIST_NUMBERING.matcher(text).replaceAll("");
        text = SAMPLE_LABEL.matcher(text).replaceAll("$1：");
        Set<Double> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            result.add(round6(Double.parseDouble(matcher.group())));
        }
        return result;
    }

    /** round() 为银行家舍入(HALF_EVEN)。 */
    private static double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_EVEN).doubleValue();
    }
}
