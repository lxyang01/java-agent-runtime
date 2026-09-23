package io.github.lxyang01.agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StringsTest {

    @Test
    void len_counts_code_points() {
        // len() 语义:emoji 一个码点,Java String.length() 会算 2
        assertThat(Strings.len("a😀b")).isEqualTo(3);
        assertThat(Strings.len("中文")).isEqualTo(2);
    }

    @Test
    void truncate_is_code_point_safe() {
        assertThat(Strings.truncate("a😀b", 2)).isEqualTo("a😀");
        assertThat(Strings.truncate("abc", 5)).isEqualTo("abc");
    }

    @Test
    void truncate_on_boundary_keeps_string() {
        assertThat(Strings.truncate("中文", 2)).isEqualTo("中文");
    }
}
