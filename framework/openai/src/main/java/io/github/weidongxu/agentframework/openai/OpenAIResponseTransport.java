package io.github.weidongxu.agentframework.openai;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

import java.util.concurrent.CompletionStage;

interface OpenAIResponseTransport {
    CompletionStage<Response> create(ResponseCreateParams params);

    StreamResponse<ResponseStreamEvent> createStreaming(ResponseCreateParams params);
}
