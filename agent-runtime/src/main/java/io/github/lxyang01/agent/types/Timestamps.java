package io.github.lxyang01.agent.types;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;

/**
 * 时间戳统一为 ISO-8601 微秒 + 固定 "+00:00" 后缀的字符串。
 *
 * <p>契约保形:PG 中时间列均为 TEXT,Python 版写 {@code datetime.now(timezone.utc).isoformat()}
 * (形如 {@code 2026-01-02T03:04:05.123456+00:00},字典序可比)。Java 侧用小写 {@code xxx}
 * 偏移模式强制 "+00:00"(ISO 默认会把零偏移打成 "Z",与 Python 存量混排会破坏字典序)。
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    public static String nowIso() {
        return iso(Instant.now());
    }

    public static String iso(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)
            .format(FORMATTER);
    }

    private Timestamps() {}
}
