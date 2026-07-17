package io.github.weidongxu.agentframework.agentserver.responses;

/**
 * Per-request context passed to a {@link ResponseHandler}, carrying the platform identity and the
 * negotiated response mode. Java counterpart of {@code Azure.AI.AgentServer.Responses.ResponseContext},
 * trimmed to what the handler needs. The host binding constructs it.
 */
public final class ResponseContext {

    private final PlatformContext platformContext;
    private final boolean streamRequested;

    public ResponseContext(PlatformContext platformContext, boolean streamRequested) {
        this.platformContext = platformContext == null ? PlatformContext.EMPTY : platformContext;
        this.streamRequested = streamRequested;
    }

    /** The platform-injected identity ({@code userId} / {@code callId}) for this request. */
    public PlatformContext platformContext() {
        return platformContext;
    }

    /**
     * Whether the caller requested a streaming ({@code text/event-stream}) response. The host
     * computes this from the request body's {@code stream} flag and/or the {@code Accept} header.
     */
    public boolean streamRequested() {
        return streamRequested;
    }
}
