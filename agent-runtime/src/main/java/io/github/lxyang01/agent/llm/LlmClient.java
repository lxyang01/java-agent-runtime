package io.github.lxyang01.agent.llm;

/**
 * 模型边界 —— 运行时对 LLM 的全部认知。
 * 生产实现 SpringAiLlmClient(M2);测试替身 ScriptedLlm / FinalLlm。
 */
public interface LlmClient {

    LlmResult complete(LlmRequest request);
}
