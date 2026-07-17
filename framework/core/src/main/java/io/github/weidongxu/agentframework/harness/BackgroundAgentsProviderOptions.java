package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.Agent;

import java.util.Map;
import java.util.function.Function;

/**
 * Options controlling the behavior of {@link BackgroundAgentsProvider}, mirroring the MAF
 * {@code BackgroundAgentsProviderOptions}.
 */
public final class BackgroundAgentsProviderOptions {
    private String instructions;
    private Function<Map<String, Agent>, String> agentListBuilder;

    /** Returns an options instance with all defaults. */
    public static BackgroundAgentsProviderOptions defaults() {
        return new BackgroundAgentsProviderOptions();
    }

    /**
     * Custom instructions for using the background-agent tools. Use the {@code {background_agents}}
     * placeholder to inject the formatted list of available agents. When {@code null} (the default),
     * built-in instructions are used.
     */
    public String getInstructions() {
        return instructions;
    }

    public BackgroundAgentsProviderOptions setInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /**
     * A custom function that builds the agent-list text appended to the instructions. Receives the
     * map of available agents keyed by name. When {@code null} (the default), a standard list of
     * agent names and descriptions is generated.
     */
    public Function<Map<String, Agent>, String> getAgentListBuilder() {
        return agentListBuilder;
    }

    public BackgroundAgentsProviderOptions setAgentListBuilder(
            Function<Map<String, Agent>, String> agentListBuilder) {
        this.agentListBuilder = agentListBuilder;
        return this;
    }
}
