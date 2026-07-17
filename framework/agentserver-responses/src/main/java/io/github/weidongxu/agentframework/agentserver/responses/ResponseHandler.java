package io.github.weidongxu.agentframework.agentserver.responses;

/**
 * The server-side extension point for the Foundry {@code POST /responses} protocol — the Java
 * counterpart of the Microsoft Agent Framework {@code ResponseHandler} (.NET
 * {@code Azure.AI.AgentServer.Responses.ResponseHandler}, Python {@code ResponseProviderProtocol}).
 *
 * <p>A handler receives the parsed request, the platform context, and a transport-neutral
 * {@link ResponseSink}, runs the agent, and writes either a buffered JSON envelope or a streaming
 * SSE sequence through the sink. Host bindings (e.g. {@code agentserver-spring}) adapt native HTTP
 * to this interface; the Foundry handler ({@code agentserver-foundry}) implements the agent-run
 * logic, conversation threading, and Responses envelope shaping.</p>
 */
public interface ResponseHandler {

    /**
     * Handles one {@code POST /responses} request. Implementations decide buffered vs. streaming
     * (typically via {@link ResponseContext#streamRequested()}) and write the result through
     * {@code sink}.
     *
     * <p>Streaming contract: once {@link ResponseSink#beginSse()} has committed the response body, a
     * handler MUST NOT propagate an exception to the host — it must instead emit a terminal error
     * event on the stream and return, because the host can no longer produce a clean error response.</p>
     *
     * @param request the parsed request body
     * @param context the platform identity and negotiated response mode
     * @param sink    the transport-neutral response writer
     */
    void handle(ResponseRequest request, ResponseContext context, ResponseSink sink) throws Exception;
}
