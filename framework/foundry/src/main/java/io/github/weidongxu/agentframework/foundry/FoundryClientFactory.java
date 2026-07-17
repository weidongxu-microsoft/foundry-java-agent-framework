package io.github.weidongxu.agentframework.foundry;

import com.azure.ai.agents.AgentsClientBuilder;
import com.azure.ai.agents.BetaMemoryStoresClient;
import com.azure.ai.agents.ResponsesClient;
import com.azure.core.credential.TokenCredential;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;

import java.util.Objects;
import java.util.concurrent.Executor;

public final class FoundryClientFactory {
    private final String projectEndpoint;
    private final TokenCredential credential;

    public FoundryClientFactory(
            String projectEndpoint,
            TokenCredential credential) {
        Objects.requireNonNull(projectEndpoint, "projectEndpoint");
        if (projectEndpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "projectEndpoint cannot be blank");
        }
        this.projectEndpoint = projectEndpoint;
        this.credential = Objects.requireNonNull(credential, "credential");
    }

    public AgentsClientBuilder createClientBuilder() {
        return new AgentsClientBuilder()
                .endpoint(projectEndpoint)
                .credential(credential)
                // Forward x-agent-foundry-call-id (when bound to the calling thread) on every
                // outbound call to Foundry platform services (Storage/Toolboxes/A2A).
                .addPolicy(new FoundryCallIdPolicy());
    }

    public ResponsesClient createResponsesClient() {
        return createClientBuilder().buildResponsesClient();
    }

    public BetaMemoryStoresClient createMemoryStoresClient() {
        return createClientBuilder()
                .beta()
                .buildBetaMemoryStoresClient();
    }

    public FoundryMemoryClient createMemoryClient(Executor executor) {
        return new SdkFoundryMemoryClient(createMemoryStoresClient(), executor);
    }

    public ChatClient createChatClient(
            Executor requestExecutor,
            Executor streamingExecutor) {
        return new OpenAIResponsesChatClient(
                createResponsesClient().getResponseService(),
                Objects.requireNonNull(requestExecutor, "requestExecutor"),
                Objects.requireNonNull(
                        streamingExecutor,
                        "streamingExecutor"));
    }
}
