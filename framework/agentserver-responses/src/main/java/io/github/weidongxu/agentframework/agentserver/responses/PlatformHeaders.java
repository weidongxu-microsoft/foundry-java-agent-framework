package io.github.weidongxu.agentframework.agentserver.responses;

/**
 * HTTP header names that form the wire contract between the Foundry platform, agent containers, and
 * downstream storage services. Java counterpart of {@code Azure.AI.AgentServer.Core.PlatformHeaders}.
 */
public final class PlatformHeaders {

    private PlatformHeaders() {
    }

    /**
     * {@code x-request-id} — request correlation id. Clients may send it; the server always echoes
     * it back on the response (incoming value, else a generated id).
     */
    public static final String REQUEST_ID = "x-request-id";

    /**
     * {@code x-platform-server} — identifies the server SDK stack (hosting version, protocol
     * versions, language, runtime). Set on every response.
     */
    public static final String SERVER_VERSION = "x-platform-server";

    /** {@code x-agent-session-id} — the resolved session id for the request (when applicable). */
    public static final String SESSION_ID = "x-agent-session-id";

    /**
     * {@code x-agent-user-id} — the platform-injected global, cross-agent per-user partition key
     * (container protocol {@code 2.0.0}). Consumed container-side to partition per-user state; it is
     * NOT forwarded on outbound first-party calls.
     */
    public static final String USER_ID = "x-agent-user-id";

    /**
     * {@code x-agent-user-isolation-key} — the container-protocol {@code 1.0.0} predecessor of
     * {@link #USER_ID}. Honored as a fallback so both protocol versions partition per-user state.
     */
    public static final String USER_ISOLATION_KEY = "x-agent-user-isolation-key";

    /**
     * {@code x-agent-foundry-call-id} — the platform-minted opaque per-request call id (container
     * protocol {@code 2.0.0}). The container MUST forward it verbatim on outbound calls to Foundry
     * platform services (Storage, Toolboxes/MCP, A2A) so those services resolve the caller context.
     * Never parsed.
     */
    public static final String FOUNDRY_CALL_ID = "x-agent-foundry-call-id";

    /** {@code x-client-} — prefix for pass-through client headers forwarded to the handler. */
    public static final String CLIENT_HEADER_PREFIX = "x-client-";

    /** {@code traceparent} — W3C Trace Context propagation header. */
    public static final String TRACE_PARENT = "traceparent";

    /**
     * {@code x-platform-error-source} — classifies error responses so the platform can route them.
     * Values: {@link #ERROR_SOURCE_USER}, {@link #ERROR_SOURCE_PLATFORM}, {@link #ERROR_SOURCE_UPSTREAM}.
     */
    public static final String ERROR_SOURCE = "x-platform-error-source";

    /** Error-source value: the caller's input is invalid and can be fixed and retried. */
    public static final String ERROR_SOURCE_USER = "user";

    /** Error-source value: the SDK/library/platform dependency failed (not the caller or handler). */
    public static final String ERROR_SOURCE_PLATFORM = "platform";

    /** Error-source value: the handler code or an external service it called failed. */
    public static final String ERROR_SOURCE_UPSTREAM = "upstream";
}
