package io.github.lxyang01.agent.guardrail;

/** 护栏违例(GuardrailError(ValueError),web 层映射 400)。 */
public class GuardrailException extends IllegalArgumentException {

    public GuardrailException(String message) {
        super(message);
    }
}
