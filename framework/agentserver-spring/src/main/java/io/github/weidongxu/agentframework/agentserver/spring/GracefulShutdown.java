package io.github.weidongxu.agentframework.agentserver.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;

/**
 * Drains in-flight requests on shutdown before the container exits — the Java equivalent of the
 * request-draining {@code Azure.AI.AgentServer.Core} performs on SIGTERM (MAF default: 30 s).
 *
 * <p>Registered as a Spring {@link SmartLifecycle} bean so its {@link #stop()} runs during graceful
 * context shutdown. It polls the {@link InFlightRequestTracker} until it reaches zero or the drain
 * timeout elapses. Pair with Spring Boot's {@code server.shutdown=graceful} so the servlet container
 * also stops accepting new connections while this drains the requests already accepted.</p>
 */
public final class GracefulShutdown implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdown.class);

    private final InFlightRequestTracker tracker;
    private final Duration drainTimeout;
    private final long pollMillis;
    private volatile boolean running;

    public GracefulShutdown(InFlightRequestTracker tracker) {
        this(tracker, Duration.ofSeconds(30));
    }

    public GracefulShutdown(InFlightRequestTracker tracker, Duration drainTimeout) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
        this.pollMillis = 100L;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        int remaining = tracker.count();
        if (remaining <= 0) {
            return;
        }
        LOG.info("graceful shutdown: draining {} in-flight request(s), timeout={}s",
                remaining, drainTimeout.getSeconds());
        while (tracker.count() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        remaining = tracker.count();
        if (remaining > 0) {
            LOG.warn("graceful shutdown: drain timed out with {} request(s) still in-flight",
                    remaining);
        } else {
            LOG.info("graceful shutdown: drain complete");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Stop late (high phase stops first) so requests drain before other beans tear down.
        return Integer.MAX_VALUE;
    }
}
