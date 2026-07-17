package io.github.weidongxu.agentframework.chat;

import java.util.Objects;

/**
 * Well-known platform request-header names and the rule for which ones a hosted agent may forward on
 * <em>outbound</em> calls it makes while serving a request (to its model, to Foundry Storage, to
 * MCP/toolbox servers).
 *
 * <p>These headers arrive inbound on the Foundry Responses protocol and are stashed on the run's
 * {@link ChatOptions} additional properties by the hosting layer. Client adapters (e.g. the OpenAI
 * Responses client, the MCP client) read them from here so they need not depend on the Foundry
 * module.</p>
 *
 * <p><strong>Forward the call id only.</strong> {@link #CALL_ID} correlates the outbound call with
 * the platform request and is safe to propagate. {@link #USER_ID} is end-user identity and must
 * <em>never</em> be echoed on outbound calls — doing so would leak user identity to downstream
 * services. Hence {@link #outboundCallId(ChatOptions)} exposes only the call id.</p>
 */
public final class PlatformRequestHeaders {

    /** End-user identity header (inbound only — never forwarded outbound). */
    public static final String USER_ID = "x-agent-user-id";

    /** Platform-minted call-correlation header, forwarded verbatim on outbound calls. */
    public static final String CALL_ID = "x-agent-foundry-call-id";

    private PlatformRequestHeaders() {
    }

    /**
     * Returns the platform call id carried on the run's options (to forward on outbound calls), or
     * {@code null} when absent or blank.
     */
    public static String outboundCallId(ChatOptions options) {
        Object value = Objects.requireNonNull(options, "options")
                .getAdditionalProperties()
                .get(CALL_ID);
        return value instanceof String && !((String) value).trim().isEmpty()
                ? (String) value
                : null;
    }
}
