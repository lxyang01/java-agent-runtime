package io.github.lxyang01.billguard.skills;

import io.github.lxyang01.agent.skill.SkillException;
import io.github.lxyang01.agent.skill.SkillMetadata;
import io.github.lxyang01.agent.skill.SkillSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 类路径技能源:skills 目录下各技能的 SKILL.md 与 routes.json(支持 fat jar)。 */
public final class ClasspathSkillSource implements SkillSource {

    private final PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver();

    @Override
    public List<SkillMetadata> list() {
        List<SkillMetadata> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath:/skills/*/SKILL.md")) {
                String url = resource.getURL().toString();
                String dir = url.substring(0, url.lastIndexOf('/'));
                // 相对路径 name/SKILL.md:校验(目录名==技能名)在两种源下统一成立
                found.add(new SkillMetadata(
                    dir.substring(dir.lastIndexOf('/') + 1), "",
                    Path.of(dir.substring(dir.lastIndexOf('/') + 1), "SKILL.md")));
            }
        } catch (IOException e) {
            throw new SkillException("cannot list classpath skills", e);
        }
        found.sort(java.util.Comparator.comparing(SkillMetadata::name));
        return found;
    }

    @Override
    public String readSkill(String name) throws IOException {
        Resource resource = resolver.getResource("classpath:/skills/" + name + "/SKILL.md");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public String readRoutes() throws IOException {
        Resource resource = resolver.getResource("classpath:/skills/routes.json");
        return resource.exists()
            ? new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8) : null;
    }
}
