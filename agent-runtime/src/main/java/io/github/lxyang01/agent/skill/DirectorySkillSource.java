package io.github.lxyang01.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 文件系统技能来源:root 下每个技能目录的 SKILL.md,加根目录 routes.json。 */
public final class DirectorySkillSource implements SkillSource {

    private final Path root;

    public DirectorySkillSource(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    @Override
    public List<SkillMetadata> list() {
        if (!Files.isDirectory(root)) {
            throw new SkillException("skill root does not exist: " + root);
        }
        List<SkillMetadata> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(dir -> {
                    Path skillMd = dir.resolve("SKILL.md");
                    if (Files.isRegularFile(skillMd)) {
                        found.add(new SkillMetadata(
                            dir.getFileName().toString(), "", skillMd));
                    }
                });
        } catch (IOException e) {
            throw new SkillException("cannot list skills under " + root, e);
        }
        return found;
    }

    @Override
    public String readSkill(String name) throws IOException {
        return Files.readString(root.resolve(name).resolve("SKILL.md"), StandardCharsets.UTF_8);
    }

    @Override
    public String readRoutes() throws IOException {
        Path routes = root.resolve("routes.json");
        return Files.isRegularFile(routes)
            ? Files.readString(routes, StandardCharsets.UTF_8) : null;
    }
}
