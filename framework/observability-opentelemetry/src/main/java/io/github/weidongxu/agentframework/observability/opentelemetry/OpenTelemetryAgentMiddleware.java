package io.github.weidongxu.agentframework.observability.opentelemetry;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.chat.Usage;
import io.github.weidongxu.agentframework.middleware.AgentMiddleware;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.AgentStreamingMiddlewareNext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.Context;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class OpenTelemetryAgentMiddleware implements AgentMiddleware {
    private final Tracer tracer;
    private final OpenTelemetryOptions options;

    public OpenTelemetryAgentMiddleware(OpenTelemetry openTelemetry) {
        this(openTelemetry, OpenTelemetryOptions.defaults());
    }

    public OpenTelemetryAgentMiddleware(
            OpenTelemetry openTelemetry,
            OpenTelemetryOptions options) {
        this(
                Objects.requireNonNull(openTelemetry, "openTelemetry")
                        .getTracer(Objects.requireNonNull(options, "options")
                                .getInstrumentationName()),
                options);
    }

    public OpenTelemetryAgentMiddleware(
            Tracer tracer,
            OpenTelemetryOptions options) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public CompletionStage<AgentResponse> invoke(
            AgentMiddlewareContext context,
            AgentMiddlewareNext next) {
        Span span = startSpan(context);
        propagateContext(context, span);
        CompletionStage<AgentResponse> stage;
        try (Scope ignored = span.makeCurrent()) {
            stage = Objects.requireNonNull(
                    next.invoke(context),
                    "Agent middleware next returned null CompletionStage");
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
    public Flow.Publisher<AgentResponseUpdate> invokeStreaming(
            AgentMiddlewareContext context,
            AgentStreamingMiddlewareNext next) {
        Span span = startSpan(context);
        propagateContext(context, span);
        Flow.Publisher<AgentResponseUpdate> publisher;
        try (Scope ignored = span.makeCurrent()) {
            publisher = Objects.requireNonNull(
                    next.invoke(context),
                    "Agent middleware next returned null Publisher");
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

    private Span startSpan(AgentMiddlewareContext context) {
        Agent agent = context.getAgent();
        String identity = firstNonBlank(agent.getName(), agent.getId(), "agent");
        Span span = tracer.spanBuilder("invoke_agent " + identity)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(TelemetryAttributes.OPERATION_NAME, "invoke_agent");
        setIfPresent(span, TelemetryAttributes.AGENT_ID, agent.getId());
        setIfPresent(span, TelemetryAttributes.AGENT_NAME, agent.getName());
        setIfPresent(span, TelemetryAttributes.AGENT_DESCRIPTION, agent.getDescription());
        if (options.isCaptureSensitiveData()) {
            span.setAttribute(
                    TelemetryAttributes.INPUT_MESSAGES,
                    TelemetrySupport.messages(context.getMessages()));
        }
        return span;
    }

    private static void propagateContext(
            AgentMiddlewareContext context,
            Span span) {
        AgentRunOptions current = context.getOptions();
        context.setOptions(current.toBuilder()
                .additionalProperty(
                        TelemetryAttributes.PARENT_CONTEXT,
                        span.storeInContext(Context.current()))
                .build());
    }

    private void observe(Span span, AgentResponse response) {
        setIfPresent(span, TelemetryAttributes.RESPONSE_ID, response.getResponseId());
        observeUsage(span, response.getUsage());
        if (options.isCaptureSensitiveData()) {
            span.setAttribute(
                    TelemetryAttributes.OUTPUT_MESSAGES,
                    TelemetrySupport.messages(response.getMessages()));
        }
    }

    private static void observe(Span span, AgentResponseUpdate update) {
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "agent";
    }
}
