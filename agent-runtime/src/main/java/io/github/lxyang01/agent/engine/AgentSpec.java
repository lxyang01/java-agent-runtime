package io.github.lxyang01.agent.engine;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 声明式定义(引擎的唯一配置来源);校验消息逐字。
 * runTimeout 为 null 表示不限时。
 */
public record AgentSpec(
    String name,
    String instructions,
    List<String> toolNames,
    int maxSteps,
    Duration runTimeout,
    int summaryThreshold,
    int summaryKeepRecent,
    int toolResultContextLimit,
    int maxContextChars) {

    public AgentSpec {
        if (name == null || name.strip().isEmpty()) {
            throw new IllegalArgumentException("agent name cannot be empty");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("max_steps must be positive");
        }
        if (runTimeout != null && (runTimeout.isZero() || runTimeout.isNegative())) {
            throw new IllegalArgumentException("run_timeout must be positive when set");
        }
        if (summaryThreshold < 0 || summaryKeepRecent < 2) {
            throw new IllegalArgumentException(
                "summary thresholds must be non-negative (keep_recent >= 2)");
        }
        Set<String> unique = new HashSet<>(toolNames);
        if (unique.size() != toolNames.size()) {
            throw new IllegalArgumentException("tool_names cannot contain duplicates");
        }
        toolNames = List.copyOf(toolNames);
    }

    public static Builder builder(String name, String instructions, List<String> toolNames) {
        return new Builder(name, instructions, toolNames);
    }

    public static final class Builder {
        private final String name;
        private final String instructions;
        private final List<String> toolNames;
        private int maxSteps = 8;
        private Duration runTimeout = null;
        private int summaryThreshold = 40;
        private int summaryKeepRecent = 12;
        private int toolResultContextLimit = 1500;
        private int maxContextChars = 80_000;

        private Builder(String name, String instructions, List<String> toolNames) {
            this.name = name;
            this.instructions = instructions;
            this.toolNames = toolNames;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder runTimeout(Duration runTimeout) {
            this.runTimeout = runTimeout;
            return this;
        }

        public Builder summaryThreshold(int threshold) {
            this.summaryThreshold = threshold;
            return this;
        }

        public Builder summaryKeepRecent(int keepRecent) {
            this.summaryKeepRecent = keepRecent;
            return this;
        }

        public Builder toolResultContextLimit(int limit) {
            this.toolResultContextLimit = limit;
            return this;
        }

        public Builder maxContextChars(int chars) {
            this.maxContextChars = chars;
            return this;
        }

        public AgentSpec build() {
            return new AgentSpec(name, instructions, toolNames, maxSteps, runTimeout,
                summaryThreshold, summaryKeepRecent, toolResultContextLimit, maxContextChars);
        }
    }
}
