package io.github.lxyang01.agent.skill;

/** 技能发现/路由/激活错误(引擎转 failed 收尾)。 */
public class SkillException extends RuntimeException {

    public SkillException(String message) {
        super(message);
    }

    public SkillException(String message, Throwable cause) {
        super(message, cause);
    }
}
