package io.github.weidongxu.agentframework.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Options controlling {@link AgentModeProvider}. Mirrors MAF's {@code AgentModeProviderOptions}. */
public final class AgentModeProviderOptions {
    private String instructions;
    private List<AgentMode> modes;
    private String defaultMode;

    /**
     * Custom instructions. Must contain the {@code {available_modes}} and {@code {current_mode}}
     * placeholders. When {@code null}, the provider uses its built-in instructions.
     */
    public String getInstructions() {
        return instructions;
    }

    public AgentModeProviderOptions setInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /** The available modes. When {@code null}, the provider uses the built-in "plan"/"execute" modes. */
    public List<AgentMode> getModes() {
        return modes;
    }

    public AgentModeProviderOptions setModes(List<AgentMode> modes) {
        this.modes = modes == null ? null : Collections.unmodifiableList(new ArrayList<>(modes));
        return this;
    }

    /** The initial mode for new sessions. When {@code null}, the first configured mode is used. */
    public String getDefaultMode() {
        return defaultMode;
    }

    public AgentModeProviderOptions setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
        return this;
    }

    public static AgentModeProviderOptions defaults() {
        return new AgentModeProviderOptions();
    }

    /** An operating mode with a name and the instructions to follow while in it. */
    public static final class AgentMode {
        private final String name;
        private final String instructions;

        public AgentMode(String name, String instructions) {
            this.name = requireNonBlank(name, "name");
            this.instructions = requireNonBlank(instructions, "instructions");
        }

        public String getName() {
            return name;
        }

        public String getInstructions() {
            return instructions;
        }

        private static String requireNonBlank(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.trim().isEmpty()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}
