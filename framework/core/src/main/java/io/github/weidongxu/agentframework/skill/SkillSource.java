package io.github.weidongxu.agentframework.skill;

import java.util.List;

/**
 * A source of {@link AgentSkill}s from a specific origin (filesystem, in-memory, remote server, ...).
 * Mirrors the .NET/Python {@code AgentSkillsSource}/{@code SkillsSource} abstraction.
 *
 * <p><strong>Security:</strong> a skill source is a trust boundary. Skill names, descriptions, and
 * bodies are injected into the agent's context as-is, so only register sources for origins you trust.</p>
 */
@FunctionalInterface
public interface SkillSource {
    /**
     * Returns the skills provided by this source. Called each time the provider assembles context, so
     * implementations that touch the filesystem or network should be inexpensive or cache internally.
     */
    List<AgentSkill> getSkills();
}
