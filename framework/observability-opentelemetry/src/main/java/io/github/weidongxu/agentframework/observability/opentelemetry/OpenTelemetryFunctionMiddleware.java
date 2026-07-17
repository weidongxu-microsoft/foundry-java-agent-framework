package io.github.weidongxu.agentframework.observability.opentelemetry;

import io.github.weidongxu.agentframework.middleware.FunctionInvocationContext;
import io.github.weidongxu.agentframework.middleware.FunctionMiddleware;
import io.github.weidongxu.agentframework.middleware.FunctionMiddlewareNext;
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

public final class OpenTelemetryFunctionMiddleware implements FunctionMiddleware {
    private final Tracer tracer;
    private final OpenTelemetryOptions options;

    public OpenTelemetryFunctionMiddleware(OpenTelemetry openTelemetry) {
        this(openTelemetry, OpenTelemetryOptions.defaults());
    }

    public OpenTelemetryFunctionMiddleware(
            OpenTelemetry openTelemetry,
            OpenTelemetryOptions options) {
        this(
                Objects.requireNonNull(openTelemetry, "openTelemetry")
                        .getTracer(Objects.requireNonNull(options, "options")
                                .getInstrumentationName()),
                options);
    }

    public OpenTelemetryFunctionMiddleware(
            Tracer tracer,
            OpenTelemetryOptions options) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public CompletionStage<String> invoke(
            FunctionInvocationContext context,
            FunctionMiddlewareNext next) {
        String toolName = context.getTool().getName();
        SpanBuilder builder = tracer.spanBuilder("execute_tool " + toolName)
                .setSpanKind(SpanKind.INTERNAL);
        Object parent = context.getOptions().getAdditionalProperties()
                .get(TelemetryAttributes.PARENT_CONTEXT);
        if (parent instanceof Context) {
            builder.setParent((Context) parent);
        }
        Span span = builder.startSpan();
        span.setAttribute(TelemetryAttributes.OPERATION_NAME, "execute_tool");
        span.setAttribute(TelemetryAttributes.TOOL_NAME, toolName);
        span.setAttribute(
                TelemetryAttributes.TOOL_CALL_ID,
                context.getCall().getCallId());
        if (options.isCaptureSensitiveData()) {
            span.setAttribute(
                    TelemetryAttributes.TOOL_ARGUMENTS,
                    context.getCall().getArguments());
        }

        CompletionStage<String> stage;
        try (Scope ignored = span.makeCurrent()) {
            stage = Objects.requireNonNull(
                    next.invoke(context),
                    "Function middleware next returned null CompletionStage");
        } catch (Throwable error) {
            TelemetrySupport.fail(span, error);
            span.end();
            return CompletableFuture.failedFuture(error);
        }
        return new TracingCompletionStage<>(stage, span, result -> {
            if (options.isCaptureSensitiveData() && result != null) {
                span.setAttribute(TelemetryAttributes.TOOL_RESULT, result);
            }
        });
    }
}
