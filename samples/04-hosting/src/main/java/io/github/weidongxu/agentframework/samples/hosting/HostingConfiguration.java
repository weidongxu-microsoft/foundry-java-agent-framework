package io.github.weidongxu.agentframework.samples.hosting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agentserver.foundry.AgentResponseHandler;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseHandler;
import io.github.weidongxu.agentframework.agentserver.spring.GracefulShutdown;
import io.github.weidongxu.agentframework.agentserver.spring.HealthController;
import io.github.weidongxu.agentframework.agentserver.spring.InFlightRequestTracker;
import io.github.weidongxu.agentframework.agentserver.spring.PlatformRequestFilter;
import io.github.weidongxu.agentframework.agentserver.spring.ResponsesEndpoint;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires a minimal Responses host from framework pieces:
 * <ul>
 *   <li>{@link OpenAIResponsesChatClient} — the provider chat client;</li>
 *   <li>{@link ChatClientAgent} — runs the model/tool loop;</li>
 *   <li>{@link AgentResponseHandler} — adapts the agent to the Responses protocol;</li>
 *   <li>{@link ResponsesEndpoint} — serves {@code POST /responses}.</li>
 * </ul>
 *
 * <p>The Spring Boot {@code ObjectMapper} is autoconfigured. Compare with {@code app/}'s
 * {@code AgentConfiguration} for the full Foundry-integrated wiring (memory, hosted tools, MCP).
 */
@Configuration
public class HostingConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    @Bean
    public ChatClient chatClient(
            ExecutorService agentExecutor,
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${OPENAI_BASE_URL:}") String baseUrl) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey == null || apiKey.isBlank() ? "not-set" : apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        OpenAIClient openai = builder.build();
        return new OpenAIResponsesChatClient(openai, agentExecutor);
    }

    @Bean
    public Agent hostedAgent(
            ChatClient chatClient,
            @Value("${OPENAI_MODEL:gpt-4o-mini}") String model) {
        return ChatClientAgent.builder(chatClient)
                .name("hosted-sample-agent")
                .instructions("You are a helpful assistant. Answer concisely.")
                .chatOptions(ChatOptions.builder().modelId(model).build())
                .build();
    }

    @Bean
    public ResponseHandler responseHandler(Agent hostedAgent, ObjectMapper objectMapper) {
        return new AgentResponseHandler(hostedAgent, objectMapper);
    }

    @Bean
    public ResponsesEndpoint responsesEndpoint(ResponseHandler responseHandler) {
        return new ResponsesEndpoint(responseHandler);
    }

    @Bean
    public HealthController healthController() {
        return new HealthController();
    }

    @Bean
    public InFlightRequestTracker inFlightRequestTracker() {
        return new InFlightRequestTracker();
    }

    @Bean
    public PlatformRequestFilter platformRequestFilter(InFlightRequestTracker tracker) {
        return new PlatformRequestFilter(tracker);
    }

    @Bean
    public GracefulShutdown gracefulShutdown(InFlightRequestTracker tracker) {
        return new GracefulShutdown(tracker);
    }
}
