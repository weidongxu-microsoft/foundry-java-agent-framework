package io.github.weidongxu.agentframework.observability.opentelemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class TracingCompletionStage<T> extends CompletableFuture<T> {
    private final CompletableFuture<?> source;
    private final Span span;

    TracingCompletionStage(
            CompletionStage<T> source,
            Span span,
            Consumer<T> observer) {
        Objects.requireNonNull(source, "source");
        this.source = source.toCompletableFuture();
        this.span = Objects.requireNonNull(span, "span");
        Objects.requireNonNull(observer, "observer");
        source.whenComplete((value, error) -> {
            synchronized (this) {
                if (isDone()) {
                    return;
                }
                Throwable failure = error == null
                        ? null
                        : TelemetrySupport.unwrap(error);
                if (failure instanceof CancellationException
                        || this.source.isCancelled()) {
                    super.cancel(false);
                    span.setAttribute(TelemetryAttributes.CANCELLED, true);
                    span.end();
                    return;
                }
                try (Scope ignored = span.makeCurrent()) {
                    if (failure != null) {
                        TelemetrySupport.fail(span, failure);
                        completeExceptionally(error);
                    } else {
                        observer.accept(value);
                        complete(value);
                    }
                } catch (Throwable observerError) {
                    TelemetrySupport.fail(span, observerError);
                    completeExceptionally(observerError);
                } finally {
                    span.end();
                }
            }
        });
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (this) {
            if (!super.cancel(mayInterruptIfRunning)) {
                return false;
            }
            span.setAttribute(TelemetryAttributes.CANCELLED, true);
            span.end();
        }
        source.cancel(mayInterruptIfRunning);
        return true;
    }
}
