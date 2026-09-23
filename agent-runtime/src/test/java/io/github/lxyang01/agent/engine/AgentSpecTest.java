package io.github.lxyang01.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentSpecTest {

    @Test
    void defaults_match_reference_semantics() {
        var spec = AgentSpec.builder("bill-agent", "指令", List.of("t1")).build();
        assertThat(spec.maxSteps()).isEqualTo(8);
        assertThat(spec.runTimeout()).isNull();
        assertThat(spec.summaryThreshold()).isEqualTo(40);
        assertThat(spec.summaryKeepRecent()).isEqualTo(12);
        assertThat(spec.toolResultContextLimit()).isEqualTo(1500);
        assertThat(spec.maxContextChars()).isEqualTo(80_000);
    }

    @Test
    void empty_name_rejected() {
        assertThatThrownBy(() -> AgentSpec.builder("  ", "i", List.of("t")).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("agent name cannot be empty");
    }

    @Test
    void non_positive_max_steps_rejected() {
        assertThatThrownBy(() -> AgentSpec.builder("a", "i", List.of("t")).maxSteps(0).build())
            .hasMessage("max_steps must be positive");
    }

    @Test
    void non_positive_timeout_rejected() {
        assertThatThrownBy(() ->
            AgentSpec.builder("a", "i", List.of("t")).runTimeout(Duration.ZERO).build())
            .hasMessage("run_timeout must be positive when set");
    }

    @Test
    void bad_summary_thresholds_rejected() {
        assertThatThrownBy(() ->
            AgentSpec.builder("a", "i", List.of("t")).summaryKeepRecent(1).build())
            .hasMessage("summary thresholds must be non-negative (keep_recent >= 2)");
    }

    @Test
    void duplicate_tools_rejected() {
        assertThatThrownBy(() -> AgentSpec.builder("a", "i", List.of("t", "t")).build())
            .hasMessage("tool_names cannot contain duplicates");
    }
}
