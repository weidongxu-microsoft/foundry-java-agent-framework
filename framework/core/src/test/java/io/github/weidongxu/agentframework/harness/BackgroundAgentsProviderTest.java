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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundAgentsProviderTest {

    /** A controllable agent whose runs complete only when the test explicitly resolves them. */
    static final class FakeAgent implements Agent {
        private final String name;
        private final String description;
        final Deque<CompletableFuture<AgentResponse>> pending = new ArrayDeque<>();
        final List<String> inputs = new ArrayList<>();
        final List<AgentSession> sessions = new ArrayList<>();

        FakeAgent(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public CompletionStage<AgentSession> createSession() {
            return CompletableFuture.completedFuture(new AgentSession());
        }

        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            inputs.add(messages.get(0).getText());
            sessions.add(session);
            CompletableFuture<AgentResponse> future = new CompletableFuture<>();
            pending.addLast(future);
            return future;
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }

        void complete(String text) {
            pending.pollFirst().complete(
                    AgentResponse.builder().message(ChatMessage.assistant(text)).build());
        }

        void fail(Throwable error) {
            pending.pollFirst().completeExceptionally(error);
        }
    }

    private static final Agent STUB = new Agent() {
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

    private static AIContext provide(BackgroundAgentsProvider provider, AgentSession session) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB, session, AIContext.empty()))
                .toCompletableFuture().get();
    }

    private static Tool toolNamed(AIContext context, String name) {
        return context.getTools().stream()
                .filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static String invoke(Tool tool, Map<String, Object> args) throws Exception {
        return tool.invoke(args).toCompletableFuture().get();
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static BackgroundAgentsProvider provider(Agent... agents) {
        return new BackgroundAgentsProvider(Arrays.asList(agents));
    }

    @Test
    void advertisesInstructionsAndSixTools() throws Exception {
        BackgroundAgentsProvider provider = provider(new FakeAgent("worker", "does work"));
        AIContext context = provide(provider, new AgentSession());
        assertNotNull(context.getInstructions());
        for (String name : Arrays.asList(
                "background_agents_start_task", "background_agents_wait_for_first_completion",
                "background_agents_get_task_results", "background_agents_get_all_tasks",
                "background_agents_continue_task", "background_agents_clear_completed_task")) {
            assertNotNull(toolNamed(context, name), "missing tool " + name);
        }
    }

    @Test
    void instructionsListAgentsWithDescriptions() throws Exception {
        BackgroundAgentsProvider provider = provider(
                new FakeAgent("alpha", "the alpha agent"), new FakeAgent("beta", null));
        String instructions = provide(provider, new AgentSession()).getInstructions();
        assertTrue(instructions.contains("alpha: the alpha agent"));
        assertTrue(instructions.contains("- beta"));
    }

    @Test
    void constructorRejectsEmptyCollection() {
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundAgentsProvider(Collections.emptyList()));
    }

    @Test
    void constructorRejectsDuplicateNamesCaseInsensitive() {
        assertThrows(IllegalArgumentException.class,
                () -> provider(new FakeAgent("Worker", null), new FakeAgent("worker", null)));
    }

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> provider(new FakeAgent("  ", null)));
    }

    @Test
    void startTaskUnknownAgentReturnsError() throws Exception {
        BackgroundAgentsProvider provider = provider(new FakeAgent("worker", null));
        AgentSession session = new AgentSession();
        Tool start = toolNamed(provide(provider, session), "background_agents_start_task");
        String result = invoke(start, args("agentName", "ghost", "input", "hi", "description", "d"));
        assertTrue(result.startsWith("Error: No background agent found"));
    }

    @Test
    void startTaskThenGetResultsWhileRunning() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        String started = invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "do it", "description", "task one"));
        assertTrue(started.contains("Background task 1 started"));
        String status = invoke(toolNamed(ctx, "background_agents_get_task_results"), args("taskId", 1));
        assertEquals("Task 1 is still running.", status);
    }

    @Test
    void startCompleteThenGetResults() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "do it", "description", "task one"));
        agent.complete("all done");
        String result = invoke(toolNamed(ctx, "background_agents_get_task_results"), args("taskId", 1));
        assertEquals("all done", result);
        assertTrue(provider.getIncompleteTasks(session).isEmpty());
    }

    @Test
    void failedTaskReportsError() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "do it", "description", "task one"));
        agent.fail(new RuntimeException("boom"));
        String result = invoke(toolNamed(ctx, "background_agents_get_task_results"), args("taskId", 1));
        assertTrue(result.contains("Task failed: boom"));
    }

    @Test
    void waitForFirstCompletion() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "a", "description", "t"));
        CompletableFuture<String> waiting = toolNamed(ctx, "background_agents_wait_for_first_completion")
                .invoke(args("taskIds", Arrays.asList(1))).toCompletableFuture();
        assertFalse(waiting.isDone());
        agent.complete("finished");
        assertTrue(waiting.get().contains("Task 1 finished with status: COMPLETED"));
    }

    @Test
    void getAllTasksListsThem() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "a", "description", "first task"));
        String all = invoke(toolNamed(ctx, "background_agents_get_all_tasks"), args());
        assertTrue(all.contains("Task 1"));
        assertTrue(all.contains("first task"));
        assertTrue(all.contains("worker"));
    }

    @Test
    void continueTaskReusesSession() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "first", "description", "t"));
        agent.complete("one");
        String cont = invoke(toolNamed(ctx, "background_agents_continue_task"),
                args("taskId", 1, "text", "second"));
        assertEquals("Task 1 continued with new input.", cont);
        agent.complete("two");
        String result = invoke(toolNamed(ctx, "background_agents_get_task_results"), args("taskId", 1));
        assertEquals("two", result);
        assertEquals(Arrays.asList("first", "second"), agent.inputs);
        assertSame(agent.sessions.get(0), agent.sessions.get(1));
    }

    @Test
    void continueRunningTaskIsRejected() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "a", "description", "t"));
        String cont = invoke(toolNamed(ctx, "background_agents_continue_task"),
                args("taskId", 1, "text", "more"));
        assertTrue(cont.contains("still running"));
    }

    @Test
    void clearCompletedTaskRemovesIt() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "a", "description", "t"));
        agent.complete("done");
        assertEquals("Task 1 cleared.",
                invoke(toolNamed(ctx, "background_agents_clear_completed_task"), args("taskId", 1)));
        assertEquals("No tasks.", invoke(toolNamed(ctx, "background_agents_get_all_tasks"), args()));
    }

    @Test
    void clearRunningTaskIsRejected() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "a", "description", "t"));
        String result = invoke(toolNamed(ctx, "background_agents_clear_completed_task"), args("taskId", 1));
        assertTrue(result.contains("still running"));
    }

    @Test
    void getIncompleteTasksReturnsRunning() throws Exception {
        FakeAgent agent = new FakeAgent("worker", null);
        BackgroundAgentsProvider provider = provider(agent);
        AgentSession session = new AgentSession();
        AIContext ctx = provide(provider, session);
        invoke(toolNamed(ctx, "background_agents_start_task"),
                args("agentName", "worker", "input", "a", "description", "t"));
        assertEquals(1, provider.getIncompleteTasks(session).size());
        agent.complete("done");
        assertEquals(0, provider.getIncompleteTasks(session).size());
    }
}
