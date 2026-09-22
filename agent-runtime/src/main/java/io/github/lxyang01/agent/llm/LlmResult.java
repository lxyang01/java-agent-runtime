package io.github.lxyang01.agent.llm;

import java.util.Map;

/**
 * 模型输出。usage/model 随值携带(Python 版用 thread-local 传递,Java 改为值携带避免多线程串号);
 * raw 是模型原文,决策解析在引擎侧。
 */
public record LlmResult(String raw, Map<String, Object> usage, String model) {

    public static LlmResult of(String raw) {
        return new LlmResult(raw, Map.of(), "");
    }
}
