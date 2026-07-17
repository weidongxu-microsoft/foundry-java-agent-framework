package io.github.weidongxu.agentframework.skill;

import java.util.Objects;

/**
 * Configuration for {@link AgentSkillsProvider}. All settings are optional and have sensible
 * defaults; use {@link #builder()} to override individual settings.
 */
public final class AgentSkillsProviderOptions {
    /** Placeholder replaced with the generated skill index inside {@link #getSkillsInstructionPrompt()}. */
    public static final String SKILLS_PLACEHOLDER = "{skills}";

    private static final String DEFAULT_PROMPT =
            "You have access to skills containing domain-specific knowledge and capabilities.\n"
                    + "Each skill provides specialized instructions and reference resources for specific tasks.\n\n"
                    + "<available_skills>\n"
                    + SKILLS_PLACEHOLDER + "\n"
                    + "</available_skills>\n\n"
                    + "When a task aligns with a skill's domain, follow these steps in order:\n"
                    + "- Use `load_skill` to retrieve the skill's full instructions.\n"
                    + "- Follow the provided guidance.\n"
                    + "- Use `read_skill_resource` to read any referenced resources, using the name exactly as listed.\n"
                    + "Only load what is needed, when it is needed.";

    private final String skillsInstructionPrompt;
    private final boolean enableReadSkillResource;

    private AgentSkillsProviderOptions(Builder builder) {
        this.skillsInstructionPrompt = builder.skillsInstructionPrompt;
        this.enableReadSkillResource = builder.enableReadSkillResource;
    }

    public static AgentSkillsProviderOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The prompt template whose {@value #SKILLS_PLACEHOLDER} placeholder is replaced with the skill index. */
    public String getSkillsInstructionPrompt() {
        return skillsInstructionPrompt;
    }

    /** Whether the {@code read_skill_resource} tool is contributed (default {@code true}). */
    public boolean isEnableReadSkillResource() {
        return enableReadSkillResource;
    }

    public static final class Builder {
        private String skillsInstructionPrompt = DEFAULT_PROMPT;
        private boolean enableReadSkillResource = true;

        private Builder() {
        }

        public Builder skillsInstructionPrompt(String prompt) {
            Objects.requireNonNull(prompt, "prompt");
            if (!prompt.contains(SKILLS_PLACEHOLDER)) {
                throw new IllegalArgumentException(
                        "skillsInstructionPrompt must contain the " + SKILLS_PLACEHOLDER + " placeholder");
            }
            this.skillsInstructionPrompt = prompt;
            return this;
        }

        public Builder enableReadSkillResource(boolean enable) {
            this.enableReadSkillResource = enable;
            return this;
        }

        public AgentSkillsProviderOptions build() {
            return new AgentSkillsProviderOptions(this);
        }
    }
}
