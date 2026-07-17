package io.github.weidongxu.agentframework.agentserver.responses;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformContextTest {

    @Test
    void resolvesUserIdAndCallIdFromHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(PlatformHeaders.USER_ID, "user-1");
        headers.put(PlatformHeaders.FOUNDRY_CALL_ID, "call-1");

        PlatformContext context = PlatformContext.fromHeaders(headers::get);

        assertEquals("user-1", context.userId());
        assertEquals("call-1", context.callId());
    }

    @Test
    void fallsBackToIsolationKeyForUserId() {
        Map<String, String> headers = new HashMap<>();
        headers.put(PlatformHeaders.USER_ISOLATION_KEY, "isolation-9");

        PlatformContext context = PlatformContext.fromHeaders(headers::get);

        assertEquals("isolation-9", context.userId());
        assertNull(context.callId());
    }

    @Test
    void prefersUserIdOverIsolationKey() {
        Map<String, String> headers = new HashMap<>();
        headers.put(PlatformHeaders.USER_ID, "user-1");
        headers.put(PlatformHeaders.USER_ISOLATION_KEY, "isolation-9");

        PlatformContext context = PlatformContext.fromHeaders(headers::get);

        assertEquals("user-1", context.userId());
    }

    @Test
    void returnsEmptyWhenNoIdentityHeaders() {
        PlatformContext context = PlatformContext.fromHeaders(name -> null);

        assertSame(PlatformContext.EMPTY, context);
        assertTrue(context.isEmpty());
    }
}
