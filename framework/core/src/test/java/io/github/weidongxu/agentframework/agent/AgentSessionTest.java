package io.github.weidongxu.agentframework.agent;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentSessionTest {
    @Test
    void storesTypedStateAndReturnsImmutableSnapshots() {
        AgentSession session = new AgentSession("session-1", Collections.singletonMap("count", 1));

        assertEquals(1, session.get("count", Integer.class));
        session.put("name", "demo");
        assertEquals("demo", session.get("name"));
        assertEquals("demo", session.remove("name"));
        assertNull(session.get("name"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> session.snapshot().put("x", "y"));
    }

    @Test
    void serviceSessionIdIsDefensivelyImmutable() {
        java.util.Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("conversation_id", "conversation-1");
        AgentSession session = new AgentSession(
                "session-1",
                source,
                Collections.emptyMap());
        source.put("conversation_id", "changed");

        java.util.Map<?, ?> stored =
                (java.util.Map<?, ?>) session.getServiceSessionId();
        assertEquals("conversation-1", stored.get("conversation_id"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((java.util.Map<Object, Object>) stored).put("x", "y"));
    }
}
