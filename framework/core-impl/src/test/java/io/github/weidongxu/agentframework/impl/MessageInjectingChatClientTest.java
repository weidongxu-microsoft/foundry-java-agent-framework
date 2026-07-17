package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agent.AgentSessionCodec;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageInjectingChatClientTest {
    @Test
    void drainsQueuedMessagesIntoInitialAgentCall() throws Exception {
        RecordingClient inner = new RecordingClient(
                ChatResponse.builder().message(ChatMessage.assistant("done")).build());
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(inner);
        AgentSession session = new AgentSession();
        injecting.enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("injected")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ChatClientAgent agent = ChatClientAgent.builder(injecting).build();

        agent.run(
                        Collections.singletonList(ChatMessage.user("start")),
                        session,
                        null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(2, inner.requests.get(0).size());
        assertEquals("injected", inner.requests.get(0).get(1).getText());
        assertTrue(injecting.getPendingMessages(session)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .isEmpty());
    }

    @Test
    void loopsWhenMessagesArriveDuringServiceCall() throws Exception {
        AgentSession session = new AgentSession();
        MessageInjectingChatClient[] holder = new MessageInjectingChatClient[1];
        RecordingClient inner = new RecordingClient(
                () -> holder[0].enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("late"))),
                ChatResponse.builder().message(ChatMessage.assistant("first")).build(),
                ChatResponse.builder().message(ChatMessage.assistant("second")).build());
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(inner);
        holder[0] = injecting;

        ChatResponse response = injecting.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options(session))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("second", response.getText());
        assertEquals(2, inner.requests.size());
        assertEquals(
                List.of("start", "first", "late"),
                texts(inner.requests.get(1)));
    }

    @Test
    void yieldsToFunctionInvocationWhenToolCallIsActionable() throws Exception {
        AgentSession session = new AgentSession();
        MessageInjectingChatClient[] holder = new MessageInjectingChatClient[1];
        RecordingClient inner = new RecordingClient(
                () -> holder[0].enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("late"))),
                ChatResponse.builder()
                        .message(ChatMessage.builder(ChatRole.ASSISTANT)
                                .addContent(new FunctionCallContent(
                                        "call-1",
                                        "lookup",
                                        "{}"))
                                .build())
                        .build());
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(inner);
        holder[0] = injecting;

        injecting.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options(session))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(1, inner.requests.size());
        assertEquals(1, injecting.getPendingMessages(session)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .size());
    }

    @Test
    void serverManagedLoopSendsOnlyInjectedMessages() throws Exception {
        AgentSession session = new AgentSession();
        MessageInjectingChatClient[] holder = new MessageInjectingChatClient[1];
        RecordingClient inner = new RecordingClient(
                () -> holder[0].enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("late"))),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("first"))
                        .continuationToken("response-1")
                        .build(),
                ChatResponse.builder().message(ChatMessage.assistant("done")).build());
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(inner);
        holder[0] = injecting;

        injecting.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options(session))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(Collections.singletonList("late"), texts(inner.requests.get(1)));
        assertEquals(
                "response-1",
                inner.options.get(1).getContinuationToken());
    }

    @Test
    void streamingLoopPreservesDemandAcrossInjectedTurns() throws Exception {
        AgentSession session = new AgentSession();
        MessageInjectingChatClient[] holder = new MessageInjectingChatClient[1];
        StreamingClient inner = new StreamingClient(
                () -> holder[0].enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("late"))),
                ChatResponseUpdate.builder().role(ChatRole.ASSISTANT).text("first").build(),
                ChatResponseUpdate.builder().role(ChatRole.ASSISTANT).text("second").build());
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(inner);
        holder[0] = injecting;
        CollectingSubscriber subscriber = new CollectingSubscriber();

        injecting.getStreamingResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options(session))
                .subscribe(subscriber);
        subscriber.subscription.request(1);

        assertEquals(Collections.singletonList("first"), subscriber.texts());
        assertFalse(subscriber.completed);

        subscriber.subscription.request(1);

        assertEquals(List.of("first", "second"), subscriber.texts());
        assertTrue(subscriber.completed);
        assertEquals(
                List.of("start", "first", "late"),
                texts(inner.requests.get(1)));
    }

    @Test
    void requiresAgentSessionRunContext() {
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(new RecordingClient(
                        ChatResponse.builder().build()));

        assertThrows(
                IllegalStateException.class,
                () -> injecting.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        ChatOptions.builder().build()));
    }

    @Test
    void restoresClaimedMessagesWhenBufferedCallFails() throws Exception {
        AgentSession session = new AgentSession();
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(new FailingClient());
        injecting.enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("retry")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertThrows(
                CompletionException.class,
                () -> injecting.getResponse(
                                Collections.singletonList(ChatMessage.user("start")),
                                options(session))
                        .toCompletableFuture()
                        .join());

        assertEquals(
                "retry",
                injecting.getPendingMessages(session)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .get(0)
                        .getText());
    }

    @Test
    void restoresClaimedMessagesWhenStreamIsCancelledBeforeDemand()
            throws Exception {
        AgentSession session = new AgentSession();
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(new StreamingClient(
                        () -> {
                        },
                        ChatResponseUpdate.builder().text("unused").build()));
        injecting.enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("retry")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        CollectingSubscriber subscriber = new CollectingSubscriber();

        injecting.getStreamingResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options(session))
                .subscribe(subscriber);
        subscriber.subscription.cancel();

        assertEquals(
                "retry",
                injecting.getPendingMessages(session)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .get(0)
                        .getText());
    }

    @Test
    void rejectsConcurrentRunsForTheSameSession() throws Exception {
        AgentSession session = new AgentSession();
        PendingClient inner = new PendingClient();
        MessageInjectingChatClient injecting =
                new MessageInjectingChatClient(inner);
        injecting.enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("queued")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        injecting.getResponse(
                Collections.singletonList(ChatMessage.user("first")),
                options(session));

        assertThrows(
                IllegalStateException.class,
                () -> injecting.getResponse(
                        Collections.singletonList(ChatMessage.user("second")),
                        options(session)));
        inner.response.complete(ChatResponse.builder()
                .message(ChatMessage.assistant("done"))
                .build());
    }

    @Test
    void serializedInFlightMessagesRecoverAfterRestart() throws Exception {
        AgentSession session = new AgentSession();
        PendingClient pending = new PendingClient();
        MessageInjectingChatClient first =
                new MessageInjectingChatClient(pending);
        first.enqueueMessages(
                        session,
                        Collections.singletonList(ChatMessage.user("recover")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        first.getResponse(
                Collections.singletonList(ChatMessage.user("start")),
                options(session));

        AgentSession restored = AgentSessionCodec.standard().deserialize(
                AgentSessionCodec.standard().serialize(session));
        RecordingClient recording = new RecordingClient(
                ChatResponse.builder().message(ChatMessage.assistant("done")).build());
        MessageInjectingChatClient restarted =
                new MessageInjectingChatClient(recording);

        restarted.getResponse(
                        Collections.singletonList(ChatMessage.user("restart")),
                        options(restored))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of("restart", "recover"),
                texts(recording.requests.get(0)));
        pending.response.completeExceptionally(
                new IllegalStateException("original process stopped"));
    }

    private static ChatOptions options(AgentSession session) {
        return ChatOptions.builder()
                .additionalProperty(RunContextProperties.AGENT_SESSION, session)
                .build();
    }

    private static List<String> texts(List<ChatMessage> messages) {
        List<String> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            result.add(message.getText());
        }
        return result;
    }

    private static final class RecordingClient implements ChatClient {
        private final Runnable firstCall;
        private final List<ChatResponse> responses;
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final List<ChatOptions> options = new ArrayList<>();

        private RecordingClient(ChatResponse... responses) {
            this(() -> {
            }, responses);
        }

        private RecordingClient(Runnable firstCall, ChatResponse... responses) {
            this.firstCall = firstCall;
            this.responses = List.of(responses);
        }

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            requests.add(new ArrayList<>(messages));
            this.options.add(options);
            int index = requests.size() - 1;
            if (index == 0) {
                firstCall.run();
            }
            return CompletableFuture.completedFuture(responses.get(index));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StreamingClient implements ChatClient {
        private final Runnable firstCall;
        private final List<ChatResponseUpdate> updates;
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        private StreamingClient(
                Runnable firstCall,
                ChatResponseUpdate... updates) {
            this.firstCall = firstCall;
            this.updates = List.of(updates);
        }

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            int index = requests.size();
            requests.add(new ArrayList<>(messages));
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done && count > 0) {
                        done = true;
                        if (index == 0) {
                            firstCall.run();
                        }
                        subscriber.onNext(updates.get(index));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }

    private static final class FailingClient implements ChatClient {
        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            CompletableFuture<ChatResponse> result = new CompletableFuture<>();
            result.completeExceptionally(new IllegalStateException("failed"));
            return result;
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class PendingClient implements ChatClient {
        private final CompletableFuture<ChatResponse> response =
                new CompletableFuture<>();

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            return response;
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CollectingSubscriber
            implements Flow.Subscriber<ChatResponseUpdate> {
        private final List<ChatResponseUpdate> updates = new ArrayList<>();
        private Flow.Subscription subscription;
        private boolean completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
            updates.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        private List<String> texts() {
            List<String> result = new ArrayList<>();
            for (ChatResponseUpdate update : updates) {
                result.add(update.getText());
            }
            return result;
        }
    }
}
