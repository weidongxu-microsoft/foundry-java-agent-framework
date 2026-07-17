package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoProviderTest {

    private static final Agent STUB_AGENT = new Agent() {
        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }
    };

    private static AIContext provide(TodoProvider provider, AgentSession session) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB_AGENT, session, AIContext.empty()))
                .toCompletableFuture().get();
    }

    private static Tool toolNamed(AIContext context, String name) {
        return context.getTools().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElse(null);
    }

    private static String invoke(Tool tool, Map<String, Object> args) throws Exception {
        return tool.invoke(args).toCompletableFuture().get();
    }

    private static Map<String, Object> arg(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> todo(String title, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        if (description != null) {
            map.put("description", description);
        }
        return map;
    }

    @Test
    void advertisesInstructionsAndFiveTools() throws Exception {
        AIContext context = provide(new TodoProvider(), new AgentSession());
        assertNotNull(context.getInstructions());
        assertTrue(context.getInstructions().contains("todos_add"));
        for (String name : Arrays.asList(
                "todos_add", "todos_complete", "todos_remove",
                "todos_get_remaining", "todos_get_all")) {
            assertNotNull(toolNamed(context, name), "missing tool " + name);
        }
    }

    @Test
    void injectsCurrentListMessageByDefault() throws Exception {
        AIContext context = provide(new TodoProvider(), new AgentSession());
        assertEquals(1, context.getMessages().size());
        assertTrue(context.getMessages().get(0).getText().contains("none yet"));
    }

    @Test
    void suppressesListMessageWhenConfigured() throws Exception {
        TodoProvider provider = new TodoProvider(
                TodoProviderOptions.defaults().setSuppressTodoListMessage(true));
        AIContext context = provide(provider, new AgentSession());
        assertTrue(context.getMessages().isEmpty());
    }

    @Test
    void addCompleteRemovePersistsAcrossTurnsInSameSession() throws Exception {
        TodoProvider provider = new TodoProvider();
        AgentSession session = new AgentSession();

        AIContext turn1 = provide(provider, session);
        invoke(toolNamed(turn1, "todos_add"),
                arg("todos", Arrays.asList(todo("first", "do the thing"), todo("second", null))));

        // A fresh turn sees the persisted items via the injected message.
        AIContext turn2 = provide(provider, session);
        String listMessage = turn2.getMessages().get(0).getText();
        assertTrue(listMessage.contains("first"));
        assertTrue(listMessage.contains("second"));

        assertEquals(2, provider.getRemainingTodos(session).size());

        invoke(toolNamed(turn2, "todos_complete"),
                arg("items", Collections.singletonList(idReason(1, "done it"))));
        assertEquals(1, provider.getRemainingTodos(session).size());
        assertEquals(2, provider.getAllTodos(session).size());

        invoke(toolNamed(turn2, "todos_remove"), arg("ids", Arrays.asList(1, 2)));
        assertTrue(provider.getAllTodos(session).isEmpty());
    }

    @Test
    void getRemainingReturnsOnlyIncomplete() throws Exception {
        TodoProvider provider = new TodoProvider();
        AgentSession session = new AgentSession();
        AIContext turn = provide(provider, session);
        invoke(toolNamed(turn, "todos_add"),
                arg("todos", Arrays.asList(todo("a", null), todo("b", null))));
        invoke(toolNamed(turn, "todos_complete"),
                arg("items", Collections.singletonList(idReason(1, "x"))));

        String remaining = invoke(toolNamed(turn, "todos_get_remaining"), Collections.emptyMap());
        assertTrue(remaining.contains("\"b\""));
        assertFalse(remaining.contains("\"a\""));
    }

    @Test
    void sessionsAreIsolated() throws Exception {
        TodoProvider provider = new TodoProvider();
        AgentSession a = new AgentSession();
        AgentSession b = new AgentSession();
        invoke(toolNamed(provide(provider, a), "todos_add"),
                arg("todos", Collections.singletonList(todo("only-a", null))));
        assertEquals(1, provider.getAllTodos(a).size());
        assertTrue(provider.getAllTodos(b).isEmpty());
    }

    private static Map<String, Object> idReason(int id, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("reason", reason);
        return map;
    }
}
