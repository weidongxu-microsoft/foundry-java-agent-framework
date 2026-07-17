package io.github.weidongxu.agentframework.skill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A {@link SkillSource} backed by a fixed, in-memory list of skills. Mirrors the .NET
 * {@code AgentInMemorySkillsSource} / Python in-memory source. Primarily used for tests and for
 * inline/embedded skills.
 */
public final class InMemorySkillSource implements SkillSource {
    private final List<AgentSkill> skills;

    public InMemorySkillSource(AgentSkill... skills) {
        this(Arrays.asList(Objects.requireNonNull(skills, "skills")));
    }

    public InMemorySkillSource(List<AgentSkill> skills) {
        this.skills = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(skills, "skills")));
    }

    @Override
    public List<AgentSkill> getSkills() {
        return skills;
    }
}
