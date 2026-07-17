package io.github.weidongxu.agentframework.skill;

import java.util.Optional;

/**
 * A domain-specific capability made available to an agent: instructions plus optional supplementary
 * resources, discovered and loaded on demand. Mirrors the .NET/Python {@code AgentSkill}/{@code Skill}
 * abstraction.
 *
 * <p>Progressive disclosure: {@link #getFrontmatter()} is the always-advertised L1 metadata,
 * {@link #getContent()} is the full L2 body fetched via {@code load_skill}, and
 * {@link #getResource(String)} exposes L3 assets fetched via {@code read_skill_resource}.</p>
 *
 * <p>{@code run_skill_script} (L4) is intentionally not modelled here — script execution requires
 * sandboxing and is deferred.</p>
 */
public interface AgentSkill {
    /** The L1 discovery metadata (name, description, and optional fields). */
    SkillFrontmatter getFrontmatter();

    /** The full skill body (the {@code SKILL.md} content below the frontmatter). */
    String getContent();

    /**
     * Looks up a supplementary resource by name.
     *
     * @param name the resource name, exactly as referenced in the skill body
     * @return the resource, or {@link Optional#empty()} when no resource with that name exists
     */
    default Optional<AgentSkillResource> getResource(String name) {
        return Optional.empty();
    }
}
