package io.github.weidongxu.agentframework.agentserver.spring;

import io.github.weidongxu.agentframework.agentserver.responses.FoundryEnvironment;
import io.github.weidongxu.agentframework.agentserver.responses.PlatformContext;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseContext;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseHandler;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The thin Spring MVC binding for the AgentServer {@code POST /responses} protocol — the Java
 * stand-in for the web-stack host in {@code Azure.AI.AgentServer.Core}. It performs only transport
 * work: parse the request, resolve the {@link PlatformContext} from inbound headers, negotiate
 * buffered vs. streaming, adapt the {@link HttpServletResponse} to a {@link HttpServletResponseSink},
 * and delegate to the injected {@link ResponseHandler}. All agent logic lives in the handler
 * (typically {@code agentserver-foundry}'s {@code AgentResponseHandler}).
 */
@RestController
public final class ResponsesEndpoint {

    private final ResponseHandler handler;
    private final Duration keepAliveInterval;
    private final ScheduledExecutorService keepAliveScheduler;

    public ResponsesEndpoint(ResponseHandler handler) {
        this(handler, FoundryEnvironment.sseKeepAliveInterval());
    }

    public ResponsesEndpoint(ResponseHandler handler, Duration keepAliveInterval) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.keepAliveInterval = keepAliveInterval == null ? Duration.ZERO : keepAliveInterval;
        this.keepAliveScheduler = this.keepAliveInterval.isZero() || this.keepAliveInterval.isNegative()
                ? null
                : Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "sse-keep-alive");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PostMapping("${agent-framework.responses.path:/responses}")
    public void createResponse(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Accept", required = false) String accept,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        Objects.requireNonNull(body, "body");
        PlatformContext platform = PlatformContext.fromHeaders(request::getHeader);
        boolean streamRequested = booleanValue(body.get("stream"))
                || (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE));
        ResponseContext context = new ResponseContext(platform, streamRequested);
        try (HttpServletResponseSink sink =
                     new HttpServletResponseSink(response, keepAliveInterval, keepAliveScheduler)) {
            handler.handle(new ResponseRequest(body), context, sink);
        }
    }

    /** Overload without servlet request — resolves an empty platform context (used in tests). */
    public void createResponse(
            Map<String, Object> body,
            String accept,
            HttpServletResponse response) throws Exception {
        Objects.requireNonNull(body, "body");
        boolean streamRequested = booleanValue(body.get("stream"))
                || (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE));
        ResponseContext context = new ResponseContext(PlatformContext.EMPTY, streamRequested);
        try (HttpServletResponseSink sink =
                     new HttpServletResponseSink(response, keepAliveInterval, keepAliveScheduler)) {
            handler.handle(new ResponseRequest(body), context, sink);
        }
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean
                ? (Boolean) value
                : value != null && "true".equalsIgnoreCase(value.toString());
    }
}
