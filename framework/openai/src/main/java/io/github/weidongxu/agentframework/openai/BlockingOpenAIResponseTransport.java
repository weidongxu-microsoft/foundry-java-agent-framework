package io.github.weidongxu.agentframework.openai;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

final class BlockingOpenAIResponseTransport implements OpenAIResponseTransport {
    private final ResponseService responseService;
    private final Executor requestExecutor;

    BlockingOpenAIResponseTransport(
            ResponseService responseService,
            Executor requestExecutor) {
        this.responseService = Objects.requireNonNull(responseService, "responseService");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    }

    @Override
    public CompletionStage<Response> create(ResponseCreateParams params) {
        return CompletableFuture.supplyAsync(
                () -> responseService.create(params),
                requestExecutor);
    }

    @Override
    public StreamResponse<ResponseStreamEvent> createStreaming(ResponseCreateParams params) {
        return responseService.createStreaming(params);
    }
}
