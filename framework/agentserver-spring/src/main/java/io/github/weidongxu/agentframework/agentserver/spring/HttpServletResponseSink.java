package io.github.weidongxu.agentframework.agentserver.spring;

import io.github.weidongxu.agentframework.agentserver.responses.ResponseSink;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ResponseSink} over a {@link HttpServletResponse}. Owns the Spring/servlet transport
 * concerns the {@code agentserver-foundry} handler must stay free of: content-type negotiation, the
 * SSE header set, and — when enabled — SSE keep-alive comment frames (the Java equivalent of
 * {@code Azure.AI.AgentServer.Core}'s {@code SseKeepAliveSession}).
 *
 * <p>Close the sink after the handler returns to stop any keep-alive task.</p>
 */
final class HttpServletResponseSink implements ResponseSink, AutoCloseable {

    private final HttpServletResponse response;
    private final Duration keepAliveInterval;
    private final ScheduledExecutorService scheduler;
    private KeepAliveWriter writer;
    private ScheduledFuture<?> keepAliveTask;

    HttpServletResponseSink(
            HttpServletResponse response,
            Duration keepAliveInterval,
            ScheduledExecutorService scheduler) {
        this.response = response;
        this.keepAliveInterval = keepAliveInterval == null ? Duration.ZERO : keepAliveInterval;
        this.scheduler = scheduler;
    }

    @Override
    public PrintWriter beginSse() throws IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        writer = new KeepAliveWriter(response.getWriter());
        if (scheduler != null && !keepAliveInterval.isZero() && !keepAliveInterval.isNegative()) {
            long millis = keepAliveInterval.toMillis();
            keepAliveTask = scheduler.scheduleAtFixedRate(
                    writer::sendKeepAlive, millis, millis, TimeUnit.MILLISECONDS);
        }
        return writer;
    }

    @Override
    public void writeJson(String body) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try (PrintWriter out = response.getWriter()) {
            out.write(body);
        }
    }

    @Override
    public void writeError(int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try (PrintWriter out = response.getWriter()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
            keepAliveTask = null;
        }
    }

    /**
     * A {@link PrintWriter} that serializes all writes under a lock and injects SSE keep-alive
     * comment frames only at frame boundaries, so a keep-alive tick can never interleave inside an
     * {@code event:}/{@code data:} pair written by the handler.
     */
    private static final class KeepAliveWriter extends PrintWriter {
        private final ReentrantLock lock = new ReentrantLock();
        // SSE frames end with a blank line ("\n\n"); start true so a keep-alive before any real
        // write is still safe.
        private volatile boolean atFrameBoundary = true;

        KeepAliveWriter(PrintWriter delegate) {
            super(delegate);
        }

        @Override
        public void write(String s) {
            lock.lock();
            try {
                super.write(s);
                atFrameBoundary = s.endsWith("\n\n");
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void flush() {
            lock.lock();
            try {
                super.flush();
            } finally {
                lock.unlock();
            }
        }

        void sendKeepAlive() {
            if (!lock.tryLock()) {
                return;
            }
            try {
                if (atFrameBoundary && !super.checkError()) {
                    super.write(": keep-alive\n\n");
                    super.flush();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
