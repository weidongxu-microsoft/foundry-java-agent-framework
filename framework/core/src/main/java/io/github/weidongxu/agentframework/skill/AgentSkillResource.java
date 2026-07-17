package io.github.weidongxu.agentframework.skill;

import java.util.Objects;

/**
 * A named supplementary resource owned by an {@link AgentSkill} — reference text, a template, or any
 * other asset the skill body refers to. Resources are fetched on demand (L3 of the progressive
 * disclosure pattern) via the {@code read_skill_resource} tool.
 */
public final class AgentSkillResource {
    private final String name;
    private final String content;

    public AgentSkillResource(String name, String content) {
        this.name = Objects.requireNonNull(name, "name");
        this.content = Objects.requireNonNull(content, "content");
    }

    /** The resource name, exactly as referenced inside the skill body (e.g. {@code references/faq.md}). */
    public String getName() {
        return name;
    }

    /** The resource text content. */
    public String getContent() {
        return content;
    }
}
