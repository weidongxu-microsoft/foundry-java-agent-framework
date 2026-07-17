package io.github.weidongxu.agentframework.foundry;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;

import reactor.core.publisher.Mono;

/**
 * Forwards the current {@link FoundryCallContext#current() call id} as the
 * {@code x-agent-foundry-call-id} header on outbound Foundry requests. Installed on every client
 * built by {@link FoundryClientFactory}; a no-op when no call id is bound to the calling thread.
 */
public final class FoundryCallIdPolicy implements HttpPipelinePolicy {

    @Override
    public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        applyCallId(context);
        return next.process();
    }

    @Override
    public HttpResponse processSync(HttpPipelineCallContext context, HttpPipelineNextSyncPolicy next) {
        applyCallId(context);
        return next.processSync();
    }

    private static void applyCallId(HttpPipelineCallContext context) {
        String callId = FoundryCallContext.current();
        if (callId != null && !callId.isBlank()) {
            context.getHttpRequest().setHeader(FoundryHostedContext.CALL_ID_HEADER, callId);
        }
    }
}
