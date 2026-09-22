package io.github.lxyang01.agent.skill;

import java.nio.file.Path;

/** 技能元数据(仅 frontmatter;正文激活时才读取)。 */
public record SkillMetadata(String name, String description, Path path) {
}
