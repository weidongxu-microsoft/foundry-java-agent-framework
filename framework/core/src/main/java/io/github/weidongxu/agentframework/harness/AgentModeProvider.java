package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.harness.AgentModeProviderOptions.AgentMode;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link AIContextProvider} that tracks the agent's operating mode (e.g. "plan" or "execute") in
 * the {@link AgentSession} state bag and lets the agent query/switch modes, mirroring MAF's
 * {@code AgentModeProvider}.
 *
 * <p>Each turn it injects instructions that embed the current mode and the full catalog of modes,
 * and contributes two auto-approved tools: {@code mode_set} (switch mode) and {@code mode_get}
 * (read current mode). The current mode persists per session. When the mode is changed
 * <em>externally</em> via {@link #setMode(AgentSession, String)}, the next turn also injects a
 * one-off user notification so the model clearly sees the switch.</p>
 */
public final class AgentModeProvider extends AIContextProvider {
    private static final String STATE_KEY = AgentModeProvider.class.getName();

    private static final String DEFAULT_INSTRUCTIONS =
            "## Agent Mode\n"
                    + "\n"
                    + "- You can operate in different modes. Depending on the mode you are in, you will be "
                    + "required to follow different processes.\n"
                    + "\n"
                    + "Use the mode_get tool to check your current operating mode.\n"
                    + "Use the mode_set tool to switch between modes as your work progresses. Only use "
                    + "mode_set if the user explicitly instructs/allows you to change modes.\n"
                    + "\n"
                    + "You are currently operating in the {current_mode} mode.\n"
                    + "\n"
                    + "### Mandatory Mode based Workflow\n"
                    + "\n"
                    + "For every new substantive user request, including short factual questions, your "
                    + "behavior is determined by the mode you are in.\n"
                    + "\n"
                    + "{available_modes}";

    private static final List<AgentMode> DEFAULT_MODES = Collections.unmodifiableList(java.util.Arrays.asList(
            new AgentMode("plan",
                    "Use this mode when analyzing requirements, breaking down tasks, and creating plans. "
                            + "This is the interactive mode — ask clarifying questions, discuss options, and "
                            + "get user approval before proceeding. When approval is granted, switch to "
                            + "execute mode using the mode_set tool."),
            new AgentMode("execute",
                    "Work autonomously using your best judgment — do not ask the user questions or wait for "
                            + "feedback. Create tasks and a plan if you don't have one, make reasonable "
                            + "decisions when you hit ambiguity, mark tasks complete as you finish them, and "
                            + "keep progressing until you have a complete result.")));

    private final String instructions;
    private final List<AgentMode> modes;
    private final Set<String> validModeNames;
    private final String modeNamesDisplay;
    private final String defaultMode;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final Object nullSessionLock = new Object();
    private ModeState fallbackState;

    public AgentModeProvider() {
        this(AgentModeProviderOptions.defaults());
    }

    public AgentModeProvider(AgentModeProviderOptions options) {
        Objects.requireNonNull(options, "options");
        this.instructions = options.getInstructions() != null
                ? options.getInstructions() : DEFAULT_INSTRUCTIONS;
        this.modes = options.getModes() != null ? options.getModes() : DEFAULT_MODES;
        if (this.modes.isEmpty()) {
            throw new IllegalArgumentException("At least one mode must be configured");
        }
        this.validModeNames = new LinkedHashSet<>();
        for (AgentMode mode : this.modes) {
            if (!validModeNames.add(mode.getName())) {
                throw new IllegalArgumentException("Duplicate mode name \"" + mode.getName() + "\"");
            }
        }
        this.modeNamesDisplay = String.join("\", \"", validModeNames);
        String requestedDefault = options.getDefaultMode() != null
                ? options.getDefaultMode() : this.modes.get(0).getName();
        if (!validModeNames.contains(requestedDefault)) {
            throw new IllegalArgumentException(
                    "Default mode \"" + requestedDefault + "\" is not in the configured modes list");
        }
        this.defaultMode = requestedDefault;
    }

    @Override
    public List<String> getStateKeys() {
        return Collections.singletonList(STATE_KEY);
    }

    /** Reads the current operating mode for the session. */
    public String getMode(AgentSession session) {
        synchronized (sessionLock(session)) {
            return state(session).currentMode;
        }
    }

    /**
     * Sets the operating mode for the session out-of-band (e.g. from a {@code /mode} command). The
     * next invocation surfaces a notification message to the agent.
     *
     * @throws IllegalArgumentException if {@code mode} is not a configured mode
     */
    public void setMode(AgentSession session, String mode) {
        validateMode(mode);
        synchronized (sessionLock(session)) {
            ModeState state = state(session);
            String previous = state.currentMode;
            state.currentMode = mode;
            if (!Objects.equals(previous, mode)) {
                state.previousModeForNotification = previous;
            }
        }
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        AgentSession session = context.getSession();
        String currentMode;
        String notifyFrom;
        synchronized (sessionLock(session)) {
            ModeState state = state(session);
            currentMode = state.currentMode;
            notifyFrom = state.previousModeForNotification;
            state.previousModeForNotification = null;
        }

        AIContext.Builder builder = AIContext.builder()
                .instructions(buildInstructions(currentMode))
                .tool(setTool(session))
                .tool(getTool(session));
        if (notifyFrom != null) {
            builder.message(ChatMessage.user(
                    "[Mode changed: The operating mode has been switched from \"" + notifyFrom
                            + "\" to \"" + currentMode + "\". You must now adjust your behavior to match "
                            + "the \"" + currentMode + "\" mode.]"));
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    // ----- tools --------------------------------------------------------------------------------

    private Tool setTool(AgentSession session) {
        Map<String, Object> schema = objectSchema(
                mapOf("mode", stringProperty("The mode to switch to. Supported: \"" + modeNamesDisplay + "\".")),
                Collections.singletonList("mode"));
        return new FunctionTool(
                "mode_set",
                "Switch the agent's operating mode. Supported modes: \"" + modeNamesDisplay + "\".",
                schema,
                args -> {
                    String mode = str(args.get("mode"));
                    if (mode == null || !validModeNames.contains(mode)) {
                        return CompletableFuture.completedFuture(
                                "Invalid mode. Supported modes are: \"" + modeNamesDisplay + "\".");
                    }
                    synchronized (sessionLock(session)) {
                        state(session).currentMode = mode;
                    }
                    return CompletableFuture.completedFuture("Mode changed to \"" + mode + "\".");
                });
    }

    private Tool getTool(AgentSession session) {
        return new FunctionTool(
                "mode_get",
                "Get the agent's current operating mode.",
                objectSchema(new LinkedHashMap<>(), Collections.emptyList()),
                args -> CompletableFuture.completedFuture(getMode(session)));
    }

    // ----- instructions / state -----------------------------------------------------------------

    private String buildInstructions(String currentMode) {
        StringBuilder modesList = new StringBuilder();
        for (AgentMode mode : modes) {
            modesList.append("#### ").append(mode.getName()).append("\n\n")
                    .append(mode.getInstructions().replaceAll("\\s+$", "")).append("\n\n");
        }
        return instructions
                .replace("{available_modes}", modesList.toString())
                .replace("{current_mode}", currentMode);
    }

    private void validateMode(String mode) {
        if (mode == null || !validModeNames.contains(mode)) {
            throw new IllegalArgumentException(
                    "Invalid mode: \"" + mode + "\". Supported modes are: \"" + modeNamesDisplay + "\".");
        }
    }

    private ModeState state(AgentSession session) {
        if (session == null) {
            if (fallbackState == null) {
                fallbackState = new ModeState(defaultMode);
            }
            return fallbackState;
        }
        Object existing = session.get(STATE_KEY);
        if (existing instanceof ModeState) {
            return (ModeState) existing;
        }
        ModeState created = new ModeState(defaultMode);
        session.put(STATE_KEY, created);
        return created;
    }

    private Object sessionLock(AgentSession session) {
        if (session == null) {
            return nullSessionLock;
        }
        return sessionLocks.computeIfAbsent(session.getId(), k -> new Object());
    }

    private static final class ModeState {
        String currentMode;
        String previousModeForNotification;

        ModeState(String initialMode) {
            this.currentMode = initialMode;
        }
    }

    // ----- schema helpers -----------------------------------------------------------------------

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(required));
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
