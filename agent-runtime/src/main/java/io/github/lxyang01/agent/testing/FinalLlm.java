package io.github.lxyang01.agent.testing;

import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.llm.LlmResult;
import io.github.lxyang01.agent.util.Json;
import java.util.Map;

/** 恒返同一条 final 的模型替身(输出 {"final": answer} 协议 JSON)。 */
public final class FinalLlm implements LlmClient {

    private final String answer;

    public FinalLlm(String answer) {
        this.answer = answer;
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        return new LlmResult(Json.write(Map.of("final", answer)), Map.of(), "final-double");
    }
}
