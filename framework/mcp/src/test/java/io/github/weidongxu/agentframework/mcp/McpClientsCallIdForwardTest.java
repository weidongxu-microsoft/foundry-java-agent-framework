package io.github.weidongxu.agentframework.mcp;

import io.github.weidongxu.agentframework.chat.PlatformCallContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class McpClientsCallIdForwardTest {

    @Test
    void stampsBoundCallIdOnOutboundRequest() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://mcp.test/"));

        PlatformCallContext.runWith("call-abc", () ->
                McpClients.CALL_ID_CUSTOMIZER.customize(
                        builder, "POST", URI.create("http://mcp.test/"), "{}", null));

        HttpRequest request = builder.build();
        assertEquals(
                Optional.of("call-abc"),
                request.headers().firstValue("x-agent-foundry-call-id"));
    }

    @Test
    void doesNotStampHeaderWhenNoCallIdBound() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://mcp.test/"));

        McpClients.CALL_ID_CUSTOMIZER.customize(
                builder, "POST", URI.create("http://mcp.test/"), "{}", null);

        HttpRequest request = builder.build();
        assertFalse(request.headers().firstValue("x-agent-foundry-call-id").isPresent());
    }
}
