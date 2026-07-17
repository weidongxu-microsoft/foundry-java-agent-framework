package io.github.weidongxu.agentframework.skill;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A code-defined {@link AgentSkill} whose frontmatter, body, and resources are supplied in memory.
 * Mirrors the .NET {@code AgentInlineSkill} / Python {@code InlineSkill}. Useful for tests and for
 * skills that are generated or embedded rather than read from the filesystem.
 */
public final class InMemorySkill implements AgentSkill {
    private final SkillFrontmatter frontmatter;
    private final String content;
    private final Map<String, AgentSkillResource> resources;

    private InMemorySkill(Builder builder) {
        this.frontmatter = Objects.requireNonNull(builder.frontmatter, "frontmatter");
        this.content = Objects.requireNonNull(builder.content, "content");
        this.resources = new LinkedHashMap<>(builder.resources);
    }

    public static Builder builder(SkillFrontmatter frontmatter, String content) {
        return new Builder(frontmatter, content);
    }

    @Override
    public SkillFrontmatter getFrontmatter() {
        return frontmatter;
    }

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public Optional<AgentSkillResource> getResource(String name) {
        return Optional.ofNullable(resources.get(Objects.requireNonNull(name, "name")));
    }

    public static final class Builder {
        private final SkillFrontmatter frontmatter;
        private final String content;
        private final Map<String, AgentSkillResource> resources = new LinkedHashMap<>();

        private Builder(SkillFrontmatter frontmatter, String content) {
            this.frontmatter = frontmatter;
            this.content = content;
        }

        public Builder resource(AgentSkillResource resource) {
            Objects.requireNonNull(resource, "resource");
            resources.put(resource.getName(), resource);
            return this;
        }

        public InMemorySkill build() {
            return new InMemorySkill(this);
        }
    }
}
