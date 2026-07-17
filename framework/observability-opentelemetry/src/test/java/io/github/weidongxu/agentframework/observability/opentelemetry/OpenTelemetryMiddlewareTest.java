package io.github.weidongxu.agentframework.observability.opentelemetry;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.Usage;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.impl.MiddlewareChatClient;
import io.github.weidongxu.agentframework.middleware.FunctionInvocationContext;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryMiddlewareTest {
    @Test
    void emitsNestedAgentAndChatSpansWithoutSensitiveContent() throws Exception {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            ChatClient instrumented = new MiddlewareChatClient(
                    new RespondingClient(),
                    Collections.singletonList(
                            new OpenTelemetryChatMiddleware(telemetry.openTelemetry)));
            ChatClientAgent agent = ChatClientAgent.builder(instrumented)
                    .id("agent-1")
                    .name("helper")
                    .chatOptions(ChatOptions.builder().modelId("model-1").build())
                    .middleware(new OpenTelemetryAgentMiddleware(
                            telemetry.openTelemetry))
                    .build();

            AgentResponse response = agent.run("secret question")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("answer", response.getText());
            List<SpanData> spans = telemetry.exporter.getFinishedSpanItems();
            assertEquals(2, spans.size());
            SpanData agentSpan = span(spans, "invoke_agent");
            SpanData chatSpan = span(spans, "chat");
            assertEquals(
                    agentSpan.getSpanContext().getSpanId(),
                    chatSpan.getParentSpanContext().getSpanId());
            assertEquals("agent-1", attribute(agentSpan, TelemetryAttributes.AGENT_ID));
            assertEquals("model-1", attribute(chatSpan, TelemetryAttributes.REQUEST_MODEL));
            assertEquals(3L, longAttribute(chatSpan, TelemetryAttributes.INPUT_TOKENS));
            assertNull(attribute(agentSpan, TelemetryAttributes.INPUT_MESSAGES));
            assertNull(attribute(chatSpan, TelemetryAttributes.OUTPUT_MESSAGES));
        }
    }

    @Test
    void sensitiveCaptureRequiresExplicitOptIn() throws Exception {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            OpenTelemetryOptions options = OpenTelemetryOptions.builder()
                    .captureSensitiveData(true)
                    .build();
            ChatClient client = new MiddlewareChatClient(
                    new RespondingClient(),
                    Collections.singletonList(new OpenTelemetryChatMiddleware(
                            telemetry.openTelemetry,
                            options)));

            client.getResponse(
                            Collections.singletonList(ChatMessage.user("secret")),
                            ChatOptions.builder().modelId("model-1").build())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            SpanData span = telemetry.exporter.getFinishedSpanItems().get(0);
            assertTrue(attribute(span, TelemetryAttributes.INPUT_MESSAGES)
                    .contains("secret"));
            assertTrue(attribute(span, TelemetryAttributes.OUTPUT_MESSAGES)
                    .contains("answer"));
        }
    }

    @Test
    void streamingSpanEndsOnCompletionAndCancellation() {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            MiddlewareChatClient completing = new MiddlewareChatClient(
                    new StreamingClient(false),
                    Collections.singletonList(
                            new OpenTelemetryChatMiddleware(telemetry.openTelemetry)));
            TestSubscriber completed = new TestSubscriber();

            completing.getStreamingResponse(
                            Collections.singletonList(ChatMessage.user("hello")),
                            ChatOptions.builder().build())
                    .subscribe(completed);
            assertTrue(telemetry.exporter.getFinishedSpanItems().isEmpty());
            completed.subscription.request(1);

            assertTrue(completed.completed);
            assertEquals(1, telemetry.exporter.getFinishedSpanItems().size());

            telemetry.exporter.reset();
            MiddlewareChatClient cancellable = new MiddlewareChatClient(
                    new StreamingClient(true),
                    Collections.singletonList(
                            new OpenTelemetryChatMiddleware(telemetry.openTelemetry)));
            TestSubscriber cancelled = new TestSubscriber();
            cancellable.getStreamingResponse(
                            Collections.singletonList(ChatMessage.user("hello")),
                            ChatOptions.builder().build())
                    .subscribe(cancelled);
            cancelled.subscription.cancel();

            SpanData cancelledSpan = telemetry.exporter.getFinishedSpanItems().get(0);
            assertEquals(
                    Boolean.TRUE,
                    cancelledSpan.getAttributes().get(
                            AttributeKey.booleanKey(TelemetryAttributes.CANCELLED)));
        }
    }

    @Test
    void failedCallsRecordErrorStatus() {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            IllegalStateException failure = new IllegalStateException("model failed");
            ChatClient client = new MiddlewareChatClient(
                    new FailingClient(failure),
                    Collections.singletonList(
                            new OpenTelemetryChatMiddleware(telemetry.openTelemetry)));

            assertThrows(
                    Exception.class,
                    () -> client.getResponse(
                                    Collections.singletonList(ChatMessage.user("hello")),
                                    ChatOptions.builder().build())
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS));

            SpanData span = telemetry.exporter.getFinishedSpanItems().get(0);
            assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
            assertEquals(
                    IllegalStateException.class.getName(),
                    attribute(span, TelemetryAttributes.ERROR_TYPE));
            assertFalse(span.getEvents().isEmpty());
        }
    }

    @Test
    void bufferedCancellationEndsSpanAndCancelsSource() {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            CompletableFuture<ChatResponse> source = new CompletableFuture<>();
            ChatClient client = new MiddlewareChatClient(
                    new PendingClient(source),
                    Collections.singletonList(
                            new OpenTelemetryChatMiddleware(telemetry.openTelemetry)));

            CompletableFuture<ChatResponse> result = client.getResponse(
                            Collections.singletonList(ChatMessage.user("hello")),
                            ChatOptions.builder().build())
                    .toCompletableFuture();
            assertTrue(result.cancel(true));

            assertTrue(source.isCancelled());
            SpanData span = telemetry.exporter.getFinishedSpanItems().get(0);
            assertEquals(
                    Boolean.TRUE,
                    span.getAttributes().get(
                            AttributeKey.booleanKey(TelemetryAttributes.CANCELLED)));
        }
    }

    @Test
    void sourceCancellationIsReportedAsCancellation() {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            CompletableFuture<ChatResponse> source = new CompletableFuture<>();
            ChatClient client = new MiddlewareChatClient(
                    new PendingClient(source),
                    Collections.singletonList(
                            new OpenTelemetryChatMiddleware(telemetry.openTelemetry)));
            CompletableFuture<ChatResponse> result = client.getResponse(
                            Collections.singletonList(ChatMessage.user("hello")),
                            ChatOptions.builder().build())
                    .toCompletableFuture();

            source.cancel(false);

            assertThrows(CancellationException.class, result::join);
            SpanData span = telemetry.exporter.getFinishedSpanItems().get(0);
            assertEquals(
                    Boolean.TRUE,
                    span.getAttributes().get(
                            AttributeKey.booleanKey(TelemetryAttributes.CANCELLED)));
            assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
        }
    }

    @Test
    void streamingSubscriptionFailuresAreDeliveredToSubscriber() {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            IllegalStateException failure =
                    new IllegalStateException("subscription failed");
            ChatClient client = new MiddlewareChatClient(
                    new SubscriptionFailingClient(failure),
                    Collections.singletonList(
                            new OpenTelemetryChatMiddleware(telemetry.openTelemetry)));
            ErrorSubscriber subscriber = new ErrorSubscriber();

            client.getStreamingResponse(
                            Collections.singletonList(ChatMessage.user("hello")),
                            ChatOptions.builder().build())
                    .subscribe(subscriber);

            assertTrue(subscriber.subscribed);
            assertEquals(failure, subscriber.error);
            assertEquals(
                    StatusCode.ERROR,
                    telemetry.exporter.getFinishedSpanItems()
                            .get(0)
                            .getStatus()
                            .getStatusCode());
        }
    }

    @Test
    void functionSpansUsePropagatedParentAndSensitiveOptIn() throws Exception {
        try (TelemetryFixture telemetry = new TelemetryFixture()) {
            Span parent = telemetry.openTelemetry
                    .getTracer(OpenTelemetryOptions.DEFAULT_INSTRUMENTATION_NAME)
                    .spanBuilder("parent")
                    .startSpan();
            ChatOptions chatOptions = ChatOptions.builder()
                    .additionalProperty(
                            TelemetryAttributes.PARENT_CONTEXT,
                            parent.storeInContext(Context.root()))
                    .build();
            FunctionTool tool = new FunctionTool(
                    "lookup",
                    "Lookup",
                    Collections.emptyMap(),
                    arguments -> CompletableFuture.completedFuture("unused"));
            FunctionInvocationContext context = new FunctionInvocationContext(
                    tool,
                    new FunctionCallContent(
                            "call-1",
                            "lookup",
                            "{\"query\":\"secret\"}"),
                    Collections.singletonMap("query", "secret"),
                    chatOptions);
            OpenTelemetryFunctionMiddleware middleware =
                    new OpenTelemetryFunctionMiddleware(
                            telemetry.openTelemetry,
                            OpenTelemetryOptions.builder()
                                    .captureSensitiveData(true)
                                    .build());

            String result = middleware.invoke(
                            context,
                            ignored -> CompletableFuture.completedFuture("result"))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            parent.end();

            assertEquals("result", result);
            SpanData toolSpan = span(
                    telemetry.exporter.getFinishedSpanItems(),
                    "execute_tool");
            assertEquals(
                    parent.getSpanContext().getSpanId(),
                    toolSpan.getParentSpanContext().getSpanId());
            assertEquals("lookup", attribute(toolSpan, TelemetryAttributes.TOOL_NAME));
            assertTrue(attribute(toolSpan, TelemetryAttributes.TOOL_ARGUMENTS)
                    .contains("secret"));
            assertEquals(
                    "result",
                    attribute(toolSpan, TelemetryAttributes.TOOL_RESULT));
        }
    }

    private static SpanData span(List<SpanData> spans, String operation) {
        return spans.stream()
                .filter(span -> operation.equals(
                        attribute(span, TelemetryAttributes.OPERATION_NAME)))
                .findFirst()
                .orElseThrow();
    }

    private static String attribute(SpanData span, String name) {
        return span.getAttributes().get(AttributeKey.stringKey(name));
    }

    private static Long longAttribute(SpanData span, String name) {
        return span.getAttributes().get(AttributeKey.longKey(name));
    }

    private static final class TelemetryFixture implements AutoCloseable {
        private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        @Override
        public void close() {
            tracerProvider.close();
            exporter.close();
        }
    }

    private static final class RespondingClient implements ChatClient {
        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .message(ChatMessage.assistant("answer"))
                    .responseId("response-1")
                    .usage(new Usage(3, 4))
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FailingClient implements ChatClient {
        private final Throwable failure;

        private FailingClient(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            CompletableFuture<ChatResponse> result = new CompletableFuture<>();
            result.completeExceptionally(failure);
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
        private final CompletableFuture<ChatResponse> response;

        private PendingClient(CompletableFuture<ChatResponse> response) {
            this.response = response;
        }

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

    private static final class StreamingClient implements ChatClient {
        private final boolean neverComplete;

        private StreamingClient(boolean neverComplete) {
            this.neverComplete = neverComplete;
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
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean done = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (!neverComplete && done.compareAndSet(false, true)) {
                        subscriber.onNext(ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .text("answer")
                                .build());
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done.set(true);
                }
            });
        }
    }

    private static final class SubscriptionFailingClient implements ChatClient {
        private final RuntimeException failure;

        private SubscriptionFailingClient(RuntimeException failure) {
            this.failure = failure;
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
            return subscriber -> {
                throw failure;
            };
        }
    }

    private static final class TestSubscriber
            implements Flow.Subscriber<ChatResponseUpdate> {
        private Flow.Subscription subscription;
        private boolean completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

    private static final class ErrorSubscriber
            implements Flow.Subscriber<ChatResponseUpdate> {
        private boolean subscribed;
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscribed = true;
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onComplete() {
        }
    }
}
