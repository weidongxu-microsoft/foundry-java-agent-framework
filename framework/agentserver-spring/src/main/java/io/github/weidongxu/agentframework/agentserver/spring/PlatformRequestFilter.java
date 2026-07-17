package io.github.weidongxu.agentframework.agentserver.spring;

import io.github.weidongxu.agentframework.agentserver.responses.PlatformHeaders;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

/**
 * Core/host servlet filter — the Java equivalent of {@code Azure.AI.AgentServer.Core}'s
 * {@code RequestIdMiddleware} + {@code ServerVersionMiddleware} + {@code InboundRequestLoggingMiddleware}.
 *
 * <p>For every request it: (1) echoes an inbound {@link PlatformHeaders#REQUEST_ID} or generates one,
 * and reflects it on the response for end-to-end correlation; (2) stamps
 * {@link PlatformHeaders#SERVER_VERSION} identifying this host stack; (3) logs the request at INFO;
 * and (4) tracks the request against an optional {@link InFlightRequestTracker} so shutdown can drain
 * in-flight work.</p>
 */
public final class PlatformRequestFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformRequestFilter.class);

    /** Value stamped on {@link PlatformHeaders#SERVER_VERSION}. */
    public static final String SERVER_VERSION =
            "java-agentserver-spring; container-protocol=2.0.0; responses=1.0.0";

    private final InFlightRequestTracker inFlightTracker;

    public PlatformRequestFilter() {
        this(null);
    }

    public PlatformRequestFilter(InFlightRequestTracker inFlightTracker) {
        this.inFlightTracker = inFlightTracker;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestId = http.getHeader(PlatformHeaders.REQUEST_ID);
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }
        httpResponse.setHeader(PlatformHeaders.REQUEST_ID, requestId);
        httpResponse.setHeader(PlatformHeaders.SERVER_VERSION, SERVER_VERSION);

        if (inFlightTracker != null) {
            inFlightTracker.enter();
        }
        long startNanos = System.nanoTime();
        try {
            LOG.info("inbound: method={} path={} requestId={}",
                    http.getMethod(), http.getRequestURI(), requestId);
            chain.doFilter(request, response);
        } finally {
            if (inFlightTracker != null) {
                inFlightTracker.exit();
            }
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.info("outbound: method={} path={} status={} requestId={} durationMs={}",
                    http.getMethod(), http.getRequestURI(), httpResponse.getStatus(),
                    requestId, millis);
        }
    }
}
