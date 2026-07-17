package io.github.weidongxu.agentframework.observability.opentelemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class TracingPublisher<T> implements Flow.Publisher<T> {
    private final Flow.Publisher<T> source;
    private final Span span;
    private final Consumer<T> observer;
    private final Runnable terminalObserver;

    TracingPublisher(
            Flow.Publisher<T> source,
            Span span,
            Consumer<T> observer,
            Runnable terminalObserver) {
        this.source = Objects.requireNonNull(source, "source");
        this.span = Objects.requireNonNull(span, "span");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.terminalObserver =
                Objects.requireNonNull(terminalObserver, "terminalObserver");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> downstream) {
        Objects.requireNonNull(downstream, "downstream");
        AtomicBoolean ended = new AtomicBoolean();
        AtomicBoolean subscribed = new AtomicBoolean();
        try (Scope ignored = span.makeCurrent()) {
            source.subscribe(new Flow.Subscriber<T>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscribed.set(true);
                    downstream.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long count) {
                            try (Scope ignored = span.makeCurrent()) {
                                subscription.request(count);
                            }
                        }

                        @Override
                        public void cancel() {
                            try (Scope ignored = span.makeCurrent()) {
                                subscription.cancel();
                            } finally {
                                if (ended.compareAndSet(false, true)) {
                                    span.setAttribute(
                                            TelemetryAttributes.CANCELLED,
                                            true);
                                    terminalObserver.run();
                                    span.end();
                                }
                            }
                        }
                    });
                }

                @Override
                public void onNext(T item) {
                    try (Scope ignored = span.makeCurrent()) {
                        observer.accept(item);
                        downstream.onNext(item);
                    } catch (Throwable error) {
                        if (ended.compareAndSet(false, true)) {
                            TelemetrySupport.fail(span, error);
                            terminalObserver.run();
                            span.end();
                        }
                        throw error;
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    if (ended.compareAndSet(false, true)) {
                        TelemetrySupport.fail(span, throwable);
                        try (Scope ignored = span.makeCurrent()) {
                            terminalObserver.run();
                            downstream.onError(throwable);
                        } finally {
                            span.end();
                        }
                    }
                }

                @Override
                public void onComplete() {
                    if (ended.compareAndSet(false, true)) {
                        try (Scope ignored = span.makeCurrent()) {
                            terminalObserver.run();
                            downstream.onComplete();
                        } finally {
                            span.end();
                        }
                    }
                }
            });
        } catch (Throwable error) {
            if (ended.compareAndSet(false, true)) {
                TelemetrySupport.fail(span, error);
                try (Scope ignored = span.makeCurrent()) {
                    if (subscribed.compareAndSet(false, true)) {
                        downstream.onSubscribe(new Flow.Subscription() {
                            @Override
                            public void request(long count) {
                            }

                            @Override
                            public void cancel() {
                            }
                        });
                    }
                    downstream.onError(error);
                } finally {
                    terminalObserver.run();
                    span.end();
                }
            }
        }
    }
}
