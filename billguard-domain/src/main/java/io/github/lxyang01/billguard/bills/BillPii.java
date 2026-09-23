package io.github.lxyang01.billguard.bills;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 账单域 PII 脱敏(模式与替换符对齐 bills.py _PII_PATTERNS;键为中文标签)。 */
public final class BillPii {

    private record Pattern2(String name, Pattern pattern, String replacement) {}

    private static final Pattern2[] PATTERNS = {
        new Pattern2("订单号",
            Pattern.compile("\\b(?:SO|ORD|NO)[-_]?[A-Za-z0-9-]{5,}\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS), "[订单号]"),
        new Pattern2("手机号", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[手机号]"),
        new Pattern2("邮箱",
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[邮箱]"),
    };

    private BillPii() {}

    public static MaskResult mask(String text) {
        String masked = text;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Pattern2 pii : PATTERNS) {
            Matcher matcher = pii.pattern().matcher(masked);
            StringBuilder replaced = new StringBuilder();
            int count = 0;
            while (matcher.find()) {
                matcher.appendReplacement(replaced,
                    Matcher.quoteReplacement(pii.replacement()));
                count++;
            }
            matcher.appendTail(replaced);
            masked = replaced.toString();
            if (count > 0) {
                counts.put(pii.name(), count);
            }
        }
        return new MaskResult(masked, counts);
    }

    public record MaskResult(String text, Map<String, Integer> counts) {}
}
