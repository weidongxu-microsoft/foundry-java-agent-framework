package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPatternsTest {
    @Test
    void sequentialChainsCanonicalHistoryAndReusesSessions()
            throws Exception {
        List<Integer> inputSizes = new ArrayList<>();
        List<AgentSession> observedSessions = new ArrayList<>();
        FakeAgent first = new FakeAgent((messages, session, options) -> {
            inputSizes.add(messages.size());
            observedSessions.add(session);
            return completed("first");
        });
        FakeAgent second = new FakeAgent((messages, session, options) -> {
            inputSizes.add(messages.size());
            return completed("second");
        });
        SequentialWorkflow workflow = SequentialWorkflow.builder()
                .participant("first", first)
                .participant("second", second)
                .build();
        WorkflowSession session = new WorkflowSession();

        WorkflowRunResult result = await(workflow.run(
                List.of(user("start")),
                session));
        await(workflow.run(List.of(user("again")), session));

        assertEquals(List.of(1, 2, 1, 2), inputSizes);
        assertEquals(
                List.of("start", "first", "second"),
                texts(result.getMessages()));
        assertEquals(1, first.sessionCreations.get());
        assertEquals(1, second.sessionCreations.get());
        assertSame(observedSessions.get(0), observedSessions.get(1));
    }

    @Test
    void concurrentPreservesDeclarationOrder() throws Exception {
        CompletableFuture<AgentResponse> first = new CompletableFuture<>();
        CompletableFuture<AgentResponse> second = new CompletableFuture<>();
        ConcurrentWorkflow workflow = ConcurrentWorkflow.builder()
                .participant("first", new FakeAgent(
                        (messages, session, options) -> first))
                .participant("second", new FakeAgent(
                        (messages, session, options) -> second))
                .build();

        CompletionStage<WorkflowRunResult> result = workflow.run(
                List.of(user("start")),
                new WorkflowSession());
        second.complete(response("second"));
        first.complete(response("first"));

        assertEquals(
                List.of("first", "second"),
                texts(await(result).getMessages()));
    }

    @Test
    void concurrentCancelsSiblingOnFailure() {
        CompletableFuture<AgentResponse> pending = new CompletableFuture<>();
        CompletableFuture<AgentResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("failed"));
        ConcurrentWorkflow workflow = ConcurrentWorkflow.builder()
                .participant("failed", new FakeAgent(
                        (messages, session, options) -> failed))
                .participant("pending", new FakeAgent(
                        (messages, session, options) -> pending))
                .build();

        assertThrows(Exception.class, () -> await(workflow.run(
                List.of(user("start")),
                new WorkflowSession())));
        assertTrue(pending.isCancelled());
    }

    @Test
    void handoffCommitsParticipantStateAfterSuccessfulTransfer()
            throws Exception {
        FakeAgent triage = new FakeAgent((messages, session, options) -> {
            Tool transfer = options.getChatOptions().getTools().stream()
                    .filter(tool -> tool.getName().equals(
                            "transfer_to_specialist"))
                    .findFirst()
                    .orElseThrow();
            transfer.invoke(Collections.emptyMap())
                    .toCompletableFuture()
                    .join();
            return completed("triaged");
        });
        FakeAgent specialist = new FakeAgent(
                (messages, session, options) -> completed("resolved"));
        HandoffWorkflow workflow = HandoffWorkflow.builder()
                .id("support")
                .participant("triage", triage)
                .participant("specialist", specialist)
                .initialParticipant("triage")
                .build();
        WorkflowSession session = new WorkflowSession();

        WorkflowRunResult result = await(workflow.run(
                List.of(user("help")),
                session));

        assertEquals(
                List.of("help", "triaged", "resolved"),
                texts(result.getMessages()));
        assertEquals(
                "triage",
                session.get("workflow.handoff.support.previous"));
        assertEquals(
                "specialist",
                session.get("workflow.handoff.support.active"));
    }

    @Test
    void handoffDoesNotCommitFailedTarget() {
        FakeAgent triage = new FakeAgent((messages, session, options) -> {
            Tool transfer = options.getChatOptions().getTools().stream()
                    .filter(tool -> tool.getName().equals(
                            "transfer_to_specialist"))
                    .findFirst()
                    .orElseThrow();
            transfer.invoke(Collections.emptyMap())
                    .toCompletableFuture()
                    .join();
            return completed("triaged");
        });
        CompletableFuture<AgentResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("failed"));
        HandoffWorkflow workflow = HandoffWorkflow.builder()
                .id("failed-target")
                .participant("triage", triage)
                .participant("specialist", new FakeAgent(
                        (messages, session, options) -> failed))
                .build();
        WorkflowSession session = new WorkflowSession();

        assertThrows(Exception.class, () -> await(workflow.run(
                List.of(user("help")),
                session)));

        assertEquals(
                "triage",
                session.get("workflow.handoff.failed-target.active"));
        assertEquals(
                null,
                session.get("workflow.handoff.failed-target.previous"));
    }

    @Test
    void groupChatUsesManagerAndTerminationPolicy() throws Exception {
        AtomicInteger selections = new AtomicInteger();
        GroupChatWorkflow workflow = GroupChatWorkflow.builder()
                .participant("writer", new FakeAgent(
                        (messages, session, options) ->
                                completed("draft")))
                .participant("reviewer", new FakeAgent(
                        (messages, session, options) ->
                                completed("approved")))
                .manager((messages, participants, round) ->
                        CompletableFuture.completedFuture(
                                selections.getAndIncrement() == 0
                                        ? "writer"
                                        : "reviewer"))
                .terminationPolicy(context ->
                        context.getLastParticipant().equals("reviewer"))
                .maxRounds(4)
                .build();

        WorkflowRunResult result = await(workflow.run(
                List.of(user("write")),
                new WorkflowSession()));

        assertEquals(
                List.of("write", "draft", "approved"),
                texts(result.getMessages()));
        assertEquals(2, selections.get());
    }

    @Test
    void cancellationPropagatesToActiveAgent() {
        CompletableFuture<AgentResponse> pending = new CompletableFuture<>();
        SequentialWorkflow workflow = SequentialWorkflow.builder()
                .participant("agent", new FakeAgent(
                        (messages, session, options) -> pending))
                .build();

        WorkflowRun run = workflow.start(
                List.of(user("start")),
                new WorkflowSession());

        assertTrue(run.cancel());
        assertTrue(pending.isCancelled());
        assertEquals(WorkflowStatus.CANCELLED, run.getStatus());
    }

    private static WorkflowRunResult await(
            CompletionStage<WorkflowRunResult> stage)
            throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static CompletionStage<AgentResponse> completed(String text) {
        return CompletableFuture.completedFuture(response(text));
    }

    private static AgentResponse response(String text) {
        return AgentResponse.builder()
                .message(ChatMessage.assistant(text))
                .build();
    }

    private static ChatMessage user(String text) {
        return ChatMessage.user(text);
    }

    private static List<String> texts(List<ChatMessage> messages) {
        List<String> result = new ArrayList<>();
        messages.forEach(message -> result.add(message.getText()));
        return result;
    }

    @FunctionalInterface
    private interface RunHandler {
        CompletionStage<AgentResponse> run(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options);
    }

    private static final class FakeAgent implements Agent {
        private final RunHandler handler;
        private final AtomicInteger sessionCreations = new AtomicInteger();

        private FakeAgent(RunHandler handler) {
            this.handler = handler;
        }

        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options) {
            return handler.run(messages, session, options);
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options) {
            SubmissionPublisher<AgentResponseUpdate> publisher =
                    new SubmissionPublisher<>();
            publisher.close();
            return publisher;
        }

        @Override
        public CompletionStage<AgentSession> createSession() {
            sessionCreations.incrementAndGet();
            return CompletableFuture.completedFuture(new AgentSession());
        }
    }
}
