package io.github.lxyang01.agent.util;

/**
 * 字符串长度/截断按 code point 计,按 code point 计 与切片语义。
 * (Java {@code String.length()} 计 UTF-16 单元,emoji 等增补平面字符会多计一,长度门禁必须统一。)
 */
public final class Strings {

    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    public static String truncate(String s, int max) {
        if (max <= 0 || len(s) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max));
    }

    private Strings() {}
}
