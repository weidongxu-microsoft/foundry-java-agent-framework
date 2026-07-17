package io.github.weidongxu.agentframework.observability.opentelemetry;

import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.Usage;
import io.github.weidongxu.agentframework.middleware.ChatMiddleware;
import io.github.weidongxu.agentframework.middleware.ChatMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.ChatMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.ChatStreamingMiddlewareNext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class OpenTelemetryChatMiddleware implements ChatMiddleware {
    private final Tracer tracer;
    private final OpenTelemetryOptions options;

    public OpenTelemetryChatMiddleware(OpenTelemetry openTelemetry) {
        this(openTelemetry, OpenTelemetryOptions.defaults());
    }

    public OpenTelemetryChatMiddleware(
            OpenTelemetry openTelemetry,
            OpenTelemetryOptions options) {
        this(
                Objects.requireNonNull(openTelemetry, "openTelemetry")
                        .getTracer(Objects.requireNonNull(options, "options")
                                .getInstrumentationName()),
                options);
    }

    public OpenTelemetryChatMiddleware(
            Tracer tracer,
            OpenTelemetryOptions options) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public CompletionStage<ChatResponse> invoke(
            ChatMiddlewareContext context,
            ChatMiddlewareNext next) {
        Span span = startSpan(context);
        CompletionStage<ChatResponse> stage;
        try (Scope ignored = span.makeCurrent()) {
            stage = Objects.requireNonNull(
                    next.invoke(context),
                    "Chat middleware next returned null CompletionStage");
        } catch (Throwable error) {
            TelemetrySupport.fail(span, error);
            span.end();
            return CompletableFuture.failedFuture(error);
        }
        return new TracingCompletionStage<>(stage, span, response -> {
            if (response != null) {
                observe(span, response);
            }
        });
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> invokeStreaming(
            ChatMiddlewareContext context,
            ChatStreamingMiddlewareNext next) {
        Span span = startSpan(context);
        Flow.Publisher<ChatResponseUpdate> publisher;
        try (Scope ignored = span.makeCurrent()) {
            publisher = Objects.requireNonNull(
                    next.invoke(context),
                    "Chat middleware next returned null Publisher");
        } catch (Throwable error) {
            TelemetrySupport.fail(span, error);
            span.end();
            throw error;
        }
        StringBuilder output = new StringBuilder();
        return new TracingPublisher<>(publisher, span, update -> {
            observe(span, update);
            if (options.isCaptureSensitiveData() && !update.getText().isEmpty()) {
                output.append(update.getText());
            }
        }, () -> {
            if (output.length() > 0) {
                span.setAttribute(
                        TelemetryAttributes.OUTPUT_MESSAGES,
                        TelemetrySupport.assistantMessage(output.toString()));
            }
        });
    }

    private Span startSpan(ChatMiddlewareContext context) {
        String model = context.getOptions().getModelId();
        SpanBuilder builder = tracer.spanBuilder(model == null || model.isBlank()
                        ? "chat"
                        : "chat " + model)
                .setSpanKind(SpanKind.CLIENT);
        Object parent = context.getOptions().getAdditionalProperties()
                .get(TelemetryAttributes.PARENT_CONTEXT);
        if (parent instanceof Context) {
            builder.setParent((Context) parent);
        }
        Span span = builder.startSpan();
        span.setAttribute(TelemetryAttributes.OPERATION_NAME, "chat");
        setIfPresent(span, TelemetryAttributes.REQUEST_MODEL, model);
        if (options.isCaptureSensitiveData()) {
            span.setAttribute(
                    TelemetryAttributes.INPUT_MESSAGES,
                    TelemetrySupport.messages(context.getMessages()));
        }
        return span;
    }

    private void observe(Span span, ChatResponse response) {
        setIfPresent(span, TelemetryAttributes.RESPONSE_ID, response.getResponseId());
        observeUsage(span, response.getUsage());
        if (options.isCaptureSensitiveData()) {
            span.setAttribute(
                    TelemetryAttributes.OUTPUT_MESSAGES,
                    TelemetrySupport.messages(response.getMessages()));
        }
    }

    private static void observe(Span span, ChatResponseUpdate update) {
        setIfPresent(span, TelemetryAttributes.RESPONSE_ID, update.getResponseId());
        Object usage = update.getAdditionalProperties().get("usage");
        if (usage instanceof Usage) {
            observeUsage(span, (Usage) usage);
        }
    }

    private static void observeUsage(Span span, Usage usage) {
        if (usage != null) {
            span.setAttribute(TelemetryAttributes.INPUT_TOKENS, usage.getInputTokens());
            span.setAttribute(TelemetryAttributes.OUTPUT_TOKENS, usage.getOutputTokens());
        }
    }

    private static void setIfPresent(Span span, String name, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(name, value);
        }
    }
}
