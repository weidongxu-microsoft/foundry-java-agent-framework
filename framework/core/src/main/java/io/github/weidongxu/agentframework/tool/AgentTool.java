package io.github.weidongxu.agentframework.tool;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Exposes an {@link Agent} as a {@link Tool} so it can be invoked by another agent (or model)
 * during function calling — the "agent as tool" composition pattern.
 *
 * <p>Java counterpart of microsoft/agent-framework's {@code AIAgent.AsAIFunction()} extension. The
 * resulting tool accepts a single {@code query} string, runs the wrapped agent, and returns the
 * agent's response text. Name/description default to the agent's own metadata (name sanitized to a
 * function-safe identifier); override via {@link AgentToolOptions}.
 *
 * <p>The tool is stateful when an {@link AgentSession} is supplied in the options (conversation
 * context is preserved across calls). A shared session is not safe for concurrent invocations; when
 * no session is given, each call runs statelessly.
 */
public final class AgentTool implements Tool {

    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[^0-9A-Za-z_]+");
    private static final String DEFAULT_NAME = "invoke_agent";
    private static final String DEFAULT_DESCRIPTION = "Invoke an agent to retrieve some information.";
    private static final String DEFAULT_QUERY_DESCRIPTION = "Input query to invoke the agent.";
    private static final String QUERY_PARAM = "query";

    private final Agent agent;
    private final String name;
    private final String description;
    private final Map<String, Object> parametersSchema;
    private final AgentSession session;
    private final AgentRunOptions runOptions;
    private final ApprovalMode approvalMode;

    private AgentTool(Agent agent, AgentToolOptions options) {
        this.agent = Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(options, "options");
        this.name = resolveName(options.getName(), agent.getName());
        this.description = firstNonBlank(options.getDescription(), agent.getDescription(),
                DEFAULT_DESCRIPTION);
        String queryDescription = firstNonBlank(options.getQueryParameterDescription(), null,
                DEFAULT_QUERY_DESCRIPTION);
        this.parametersSchema = buildSchema(queryDescription);
        this.session = options.getSession();
        this.runOptions = options.getRunOptions() != null
                ? options.getRunOptions() : AgentRunOptions.empty();
        this.approvalMode = options.getApprovalMode();
    }

    /** Wraps {@code agent} with default options. */
    public static AgentTool of(Agent agent) {
        return new AgentTool(agent, AgentToolOptions.empty());
    }

    /** Wraps {@code agent} with the given options. */
    public static AgentTool of(Agent agent, AgentToolOptions options) {
        return new AgentTool(agent, options);
    }

    /** @return the wrapped agent. */
    public Agent getAgent() {
        return agent;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return parametersSchema;
    }

    @Override
    public ApprovalMode getApprovalMode() {
        return approvalMode;
    }

    @Override
    public CompletionStage<String> invoke(Map<String, Object> arguments) {
        return invoke(arguments, ToolContext.empty());
    }

    @Override
    public CompletionStage<String> invoke(Map<String, Object> arguments, ToolContext context) {
        Objects.requireNonNull(arguments, "arguments");
        String query = coerceToString(arguments.get(QUERY_PARAM));
        List<ChatMessage> messages = Collections.singletonList(ChatMessage.user(query));
        return agent.run(messages, session, runOptions)
                .thenApply(AgentTool::responseText);
    }

    private static String responseText(AgentResponse response) {
        String text = response == null ? null : response.getText();
        return text == null ? "" : text;
    }

    private static String coerceToString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String resolveName(String explicit, String agentName) {
        String candidate = firstNonBlank(explicit, agentName, DEFAULT_NAME);
        String sanitized = INVALID_NAME_CHARS.matcher(candidate).replaceAll("_");
        return sanitized.isEmpty() ? DEFAULT_NAME : sanitized;
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        if (b != null && !b.trim().isEmpty()) {
            return b;
        }
        return fallback;
    }

    private static Map<String, Object> buildSchema(String queryDescription) {
        Map<String, Object> queryProperty = new LinkedHashMap<>();
        queryProperty.put("type", "string");
        queryProperty.put("description", queryDescription);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(QUERY_PARAM, queryProperty);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList(QUERY_PARAM));
        return Collections.unmodifiableMap(schema);
    }
}
