package io.github.weidongxu.agentframework.impl;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

final class MappingPublisher<T, R> implements Flow.Publisher<R> {
    private final Flow.Publisher<T> source;
    private final Function<? super T, ? extends R> mapper;

    MappingPublisher(Flow.Publisher<T> source, Function<? super T, ? extends R> mapper) {
        this.source = Objects.requireNonNull(source, "source");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super R> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        source.subscribe(new Flow.Subscriber<T>() {
            private final AtomicBoolean done = new AtomicBoolean();
            private Flow.Subscription upstream;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.upstream = subscription;
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        if (!done.get()) {
                            upstream.request(count);
                        }
                    }

                    @Override
                    public void cancel() {
                        if (done.compareAndSet(false, true)) {
                            upstream.cancel();
                        }
                    }
                });
            }

            @Override
            public void onNext(T item) {
                if (done.get()) {
                    return;
                }
                R mapped;
                try {
                    mapped = mapper.apply(item);
                } catch (Throwable error) {
                    if (done.compareAndSet(false, true)) {
                        upstream.cancel();
                        subscriber.onError(error);
                    }
                    return;
                }
                if (!done.get()) {
                    subscriber.onNext(mapped);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (done.compareAndSet(false, true)) {
                    subscriber.onError(throwable);
                }
            }

            @Override
            public void onComplete() {
                if (done.compareAndSet(false, true)) {
                    subscriber.onComplete();
                }
            }
        });
    }
}
