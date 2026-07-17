package io.github.weidongxu.agentframework.agentserver.spring;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts in-flight {@code /responses} (and other application) requests so {@link GracefulShutdown}
 * can drain them on SIGTERM before the container exits — the Java equivalent of the request-draining
 * the {@code Azure.AI.AgentServer.Core} host performs on shutdown.
 */
public final class InFlightRequestTracker {

    private final AtomicInteger inFlight = new AtomicInteger();

    /** Marks a request as started. */
    public void enter() {
        inFlight.incrementAndGet();
    }

    /** Marks a request as finished. */
    public void exit() {
        inFlight.decrementAndGet();
    }

    /** The current number of in-flight requests. */
    public int count() {
        return inFlight.get();
    }
}
