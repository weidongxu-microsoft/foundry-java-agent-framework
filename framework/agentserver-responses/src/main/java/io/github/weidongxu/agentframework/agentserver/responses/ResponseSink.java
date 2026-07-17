package io.github.weidongxu.agentframework.agentserver.responses;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Abstraction over the HTTP response a {@link ResponseHandler} writes to, so the handler stays free
 * of any web-framework type. The host binding (e.g. {@code agentserver-spring}) supplies the concrete
 * implementation over its native response object, and owns transport concerns such as SSE keep-alive
 * framing and correlation headers.
 */
public interface ResponseSink {

    /**
     * Begins a streaming {@code text/event-stream} response and returns the writer for SSE frames.
     * The host sets the SSE headers (and may decorate the writer with keep-alive framing). Call at
     * most once per request, and not in combination with {@link #writeJson(String)}.
     */
    PrintWriter beginSse() throws IOException;

    /** Writes a buffered {@code application/json} response body. */
    void writeJson(String body) throws IOException;

    /** Writes a buffered JSON error response with the given HTTP status code. */
    void writeError(int status, String body) throws IOException;
}
