package io.github.weidongxu.agentframework.tool;

import io.github.weidongxu.agentframework.agent.AgentSession;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FunctionToolTest {
    private static final Map<String, Object> SCHEMA =
            Collections.singletonMap("type", "object");

    @Test
    void contextualHandlerReceivesToolContext() throws Exception {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        FunctionTool tool = new FunctionTool(
                "todo",
                "manage todos",
                SCHEMA,
                (ContextualToolHandler) (arguments, context) -> {
                    seen.set(context);
                    return CompletableFuture.completedFuture(
                            context.getSession().getId());
                });

        AgentSession session = new AgentSession("user-1", Collections.emptyMap());
        String result = tool.invoke(Collections.emptyMap(), new ToolContext(session))
                .toCompletableFuture().get();

        assertEquals("user-1", result);
        assertSame(session, seen.get().getSession());
    }

    @Test
    void contextFreeInvokePassesEmptyContext() throws Exception {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        FunctionTool tool = new FunctionTool(
                "todo",
                "manage todos",
                SCHEMA,
                (ContextualToolHandler) (arguments, context) -> {
                    seen.set(context);
                    return CompletableFuture.completedFuture("ok");
                });

        String result = tool.invoke(Collections.emptyMap())
                .toCompletableFuture().get();

        assertEquals("ok", result);
        assertNull(seen.get().getSession());
    }

    @Test
    void plainToolHandlerStillWorksAndIgnoresContext() throws Exception {
        FunctionTool tool = new FunctionTool(
                "todo",
                "manage todos",
                SCHEMA,
                arguments -> CompletableFuture.completedFuture(
                        String.valueOf(arguments.get("echo"))));

        AgentSession session = new AgentSession("user-2", Collections.emptyMap());
        String result = tool.invoke(
                        Collections.singletonMap("echo", "hi"),
                        new ToolContext(session))
                .toCompletableFuture().get();

        assertEquals("hi", result);
    }
}
