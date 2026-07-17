package io.github.weidongxu.agentframework.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

final class SdkOpenAIResponseTransport implements OpenAIResponseTransport {
    private final OpenAIClient client;

    SdkOpenAIResponseTransport(OpenAIClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public CompletionStage<Response> create(ResponseCreateParams params) {
        return client.async().responses().create(params);
    }

    @Override
    public StreamResponse<ResponseStreamEvent> createStreaming(ResponseCreateParams params) {
        return client.responses().createStreaming(params);
    }
}
