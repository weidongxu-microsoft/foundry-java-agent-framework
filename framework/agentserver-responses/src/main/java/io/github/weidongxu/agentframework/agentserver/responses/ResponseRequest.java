package io.github.weidongxu.agentframework.agentserver.responses;

import java.util.Collections;
import java.util.Map;

/**
 * The parsed {@code POST /responses} request body, kept as a generic map so the
 * {@code agentserver-responses} module stays free of any OpenAI Responses model types. The concrete
 * handler (e.g. {@code AgentResponseHandler} in {@code agentserver-foundry}) owns interpreting the
 * body — extracting {@code input}, {@code conversation}, {@code previous_response_id}, {@code stream},
 * tool definitions, etc.
 */
public final class ResponseRequest {

    private final Map<String, Object> body;

    public ResponseRequest(Map<String, Object> body) {
        this.body = body == null ? Collections.emptyMap() : body;
    }

    /** The raw, parsed request body. Never {@code null}. */
    public Map<String, Object> body() {
        return body;
    }
}
