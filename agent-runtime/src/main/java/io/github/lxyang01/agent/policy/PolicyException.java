package io.github.lxyang01.agent.policy;

/** 策略违例(引擎捕获后作为工具错误结果回给模型,循环不中断)。 */
public class PolicyException extends RuntimeException {

    public PolicyException(String message) {
        super(message);
    }
}
