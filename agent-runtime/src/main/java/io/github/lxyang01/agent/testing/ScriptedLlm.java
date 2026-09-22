package io.github.lxyang01.agent.testing;

import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.llm.LlmResult;
import io.github.lxyang01.agent.util.Json;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;

/** 剧本模型:每次 complete 依序弹出一条;Map/List 自动序列化为 JSON;耗尽即失败。 */
public final class ScriptedLlm implements LlmClient {

    private final Deque<Object> script;

    public ScriptedLlm(Object... outputs) {
        this.script = new ArrayDeque<>(Arrays.asList(outputs));
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        Object next = script.poll();
        if (next == null) {
            throw new AssertionError("script exhausted");
        }
        String raw = next instanceof String text ? text : Json.write(next);
        return new LlmResult(raw, Map.of(), "scripted");
    }
}
