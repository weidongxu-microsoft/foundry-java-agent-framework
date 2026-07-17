package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.harness.AgentModeProviderOptions.AgentMode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModeProviderTest {

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

    private static AIContext provide(AgentModeProvider provider, AgentSession session) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB_AGENT, session, AIContext.empty()))
                .toCompletableFuture().get();
    }

    private static Tool toolNamed(AIContext context, String name) {
        return context.getTools().stream()
                .filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static String invoke(Tool tool, Map<String, Object> args) throws Exception {
        return tool.invoke(args).toCompletableFuture().get();
    }

    private static Map<String, Object> arg(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    void defaultsToPlanModeAndAdvertisesTools() throws Exception {
        AIContext context = provide(new AgentModeProvider(), new AgentSession());
        assertTrue(context.getInstructions().contains("plan mode"));
        assertTrue(context.getInstructions().contains("#### plan"));
        assertTrue(context.getInstructions().contains("#### execute"));
        assertNotNull(toolNamed(context, "mode_set"));
        assertNotNull(toolNamed(context, "mode_get"));
    }

    @Test
    void modeSetToolPersistsAcrossTurns() throws Exception {
        AgentModeProvider provider = new AgentModeProvider();
        AgentSession session = new AgentSession();
        AIContext first = provide(provider, session);
        invoke(toolNamed(first, "mode_set"), arg("mode", "execute"));
        assertEquals("execute", provider.getMode(session));
        AIContext second = provide(provider, session);
        assertTrue(second.getInstructions().contains("execute mode"));
    }

    @Test
    void modeGetToolReturnsCurrentMode() throws Exception {
        AgentModeProvider provider = new AgentModeProvider();
        AgentSession session = new AgentSession();
        AIContext context = provide(provider, session);
        assertEquals("plan", invoke(toolNamed(context, "mode_get"), Collections.emptyMap()));
    }

    @Test
    void modeSetToolRejectsUnknownMode() throws Exception {
        AgentModeProvider provider = new AgentModeProvider();
        AgentSession session = new AgentSession();
        AIContext context = provide(provider, session);
        String result = invoke(toolNamed(context, "mode_set"), arg("mode", "bogus"));
        assertTrue(result.contains("Invalid mode"));
        assertEquals("plan", provider.getMode(session));
    }

    @Test
    void externalSetModeInjectsNotificationOnce() throws Exception {
        AgentModeProvider provider = new AgentModeProvider();
        AgentSession session = new AgentSession();
        provide(provider, session);
        provider.setMode(session, "execute");
        AIContext next = provide(provider, session);
        assertEquals(1, next.getMessages().size());
        assertTrue(next.getMessages().get(0).getText().contains("Mode changed"));
        AIContext afterThat = provide(provider, session);
        assertTrue(afterThat.getMessages().isEmpty());
    }

    @Test
    void sessionsHaveIndependentModes() throws Exception {
        AgentModeProvider provider = new AgentModeProvider();
        AgentSession first = new AgentSession();
        AgentSession second = new AgentSession();
        invoke(toolNamed(provide(provider, first), "mode_set"), arg("mode", "execute"));
        assertEquals("execute", provider.getMode(first));
        assertEquals("plan", provider.getMode(second));
    }

    @Test
    void customModesAndDefault() throws Exception {
        AgentModeProvider provider = new AgentModeProvider(AgentModeProviderOptions.defaults()
                .setModes(Arrays.asList(
                        new AgentMode("review", "Review code carefully."),
                        new AgentMode("write", "Write code.")))
                .setDefaultMode("write"));
        AgentSession session = new AgentSession();
        assertEquals("write", provider.getMode(session));
        assertTrue(provide(provider, session).getInstructions().contains("#### review"));
    }

    @Test
    void rejectsBadConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
                new AgentModeProvider(AgentModeProviderOptions.defaults()
                        .setModes(Collections.emptyList())));
        assertThrows(IllegalArgumentException.class, () ->
                new AgentModeProvider(AgentModeProviderOptions.defaults()
                        .setDefaultMode("missing")));
    }
}
