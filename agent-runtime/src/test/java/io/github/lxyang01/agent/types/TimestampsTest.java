package io.github.lxyang01.agent.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimestampsTest {

    @Test
    void nowIso_matches_python_shape() {
        assertThat(Timestamps.nowIso())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+00:00");
    }

    @Test
    void iso_is_fixed_microsecond_shape() {
        assertThat(Timestamps.iso(Instant.parse("2026-01-02T03:04:05.123456Z")))
            .isEqualTo("2026-01-02T03:04:05.123456+00:00");
    }

    @Test
    void iso_truncates_nanoseconds_to_micros() {
        assertThat(Timestamps.iso(Instant.parse("2026-01-02T03:04:05.123456789Z")))
            .isEqualTo("2026-01-02T03:04:05.123456+00:00");
    }

    @Test
    void iso_strings_are_lexicographically_sortable() {
        var earlier = Timestamps.iso(Instant.parse("2026-01-02T03:04:05Z"));
        var later = Timestamps.iso(Instant.parse("2026-01-02T03:04:06Z"));
        assertThat(earlier).isLessThan(later);
    }
}
