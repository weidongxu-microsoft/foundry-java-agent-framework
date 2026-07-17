package io.github.weidongxu.agentframework.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The L1 discovery metadata for an {@link AgentSkill}, parsed from the YAML frontmatter block of a
 * {@code SKILL.md} file. Mirrors the Agent Skills specification (see
 * <a href="https://agentskills.io/specification">agentskills.io</a>) and the .NET/Python
 * {@code AgentSkillFrontmatter}/{@code SkillFrontmatter} shape.
 *
 * <p>Only {@code name} and {@code description} are required; the remaining fields are optional and
 * default to empty. This is the metadata advertised to the model in the L1 skill index — the full
 * body is only fetched on demand via {@code load_skill}.</p>
 */
public final class SkillFrontmatter {
    private final String name;
    private final String description;
    private final String license;
    private final List<String> allowedTools;
    private final Map<String, String> metadata;

    private SkillFrontmatter(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.description = Objects.requireNonNull(builder.description, "description");
        this.license = builder.license;
        this.allowedTools = Collections.unmodifiableList(new ArrayList<>(builder.allowedTools));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    public static SkillFrontmatter of(String name, String description) {
        return builder(name, description).build();
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /** Optional SPDX-style license identifier, or {@code null} when unspecified. */
    public String getLicense() {
        return license;
    }

    /** Optional allow-list of tool names the skill may use; empty when unspecified. */
    public List<String> getAllowedTools() {
        return allowedTools;
    }

    /** Additional, source-specific frontmatter keys preserved as-is. */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private String license;
        private final List<String> allowedTools = new ArrayList<>();
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder license(String license) {
            this.license = license;
            return this;
        }

        public Builder allowedTool(String tool) {
            allowedTools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        public Builder allowedTools(List<String> tools) {
            Objects.requireNonNull(tools, "tools").forEach(this::allowedTool);
            return this;
        }

        public Builder metadata(String key, String value) {
            metadata.put(
                    Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value"));
            return this;
        }

        public SkillFrontmatter build() {
            return new SkillFrontmatter(this);
        }
    }
}
