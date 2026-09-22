package io.github.lxyang01.agent.skill;

import java.io.IOException;
import java.util.List;

/** 技能资产来源(文件系统/类classpath 各自实现;runtime 不绑定 Spring 资源抽象)。 */
public interface SkillSource {

    /** 发现全部技能(按目录名排序);无技能返回空表。 */
    List<SkillMetadata> list();

    /** 读取指定技能的 SKILL.md 全文。 */
    String readSkill(String name) throws IOException;

    /** 读取 routes.json 全文;资产缺失返回 null。 */
    String readRoutes() throws IOException;
}
