package io.github.weidongxu.agentframework.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A filesystem-backed {@link AgentSkill}: the frontmatter is parsed eagerly (needed for the L1
 * index) while the body and resources are read lazily on demand. A resource name is a POSIX-style
 * path relative to the skill directory (e.g. {@code references/faq.md}); traversal outside the skill
 * directory is rejected.
 */
final class FileSkill implements AgentSkill {
    private final Path skillDir;
    private final Path skillFile;
    private final SkillFrontmatter frontmatter;
    private final String body;

    FileSkill(Path skillDir, Path skillFile, SkillFrontmatter frontmatter, String body) {
        this.skillDir = skillDir;
        this.skillFile = skillFile;
        this.frontmatter = frontmatter;
        this.body = body;
    }

    @Override
    public SkillFrontmatter getFrontmatter() {
        return frontmatter;
    }

    @Override
    public String getContent() {
        return body;
    }

    @Override
    public Optional<AgentSkillResource> getResource(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Path normalizedDir = skillDir.toAbsolutePath().normalize();
        Path resource = normalizedDir.resolve(name).normalize();
        if (!resource.startsWith(normalizedDir) || resource.equals(skillFile.toAbsolutePath().normalize())) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(resource)) {
            return Optional.empty();
        }
        try {
            String content = new String(Files.readAllBytes(resource), StandardCharsets.UTF_8);
            return Optional.of(new AgentSkillResource(name, content));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read skill resource: " + name, e);
        }
    }
}
