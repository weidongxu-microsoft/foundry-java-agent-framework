package demo.photoprocess.withframework;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agentserver.foundry.AgentResponseHandler;
import io.github.weidongxu.agentframework.agentserver.responses.ConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.InMemoryConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.InMemoryResponseStore;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseHandler;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseStore;
import io.github.weidongxu.agentframework.agentserver.spring.GracefulShutdown;
import io.github.weidongxu.agentframework.agentserver.spring.HealthController;
import io.github.weidongxu.agentframework.agentserver.spring.InFlightRequestTracker;
import io.github.weidongxu.agentframework.agentserver.spring.PlatformRequestFilter;
import io.github.weidongxu.agentframework.agentserver.spring.ResponsesEndpoint;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.foundry.FoundryClientFactory;
import io.github.weidongxu.agentframework.foundry.FoundryMemoryClient;
import io.github.weidongxu.agentframework.foundry.FoundryMemoryProvider;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the entire photo-process hosted agent from framework pieces. Compare the size of this file
 * with the without-framework project's hand-written {@code PhotoProcessController}: the wire protocol,
 * SSE framing, {@code /readiness}, the response object, the streaming relay, and durable memory
 * (a one-line {@code FoundryMemoryProvider} attach) are all inherited here — only the crop workflow
 * ({@link PhotoProcessMiddleware}) is app code.
 */
@Configuration
public class PhotoProcessConfiguration {

    /**
     * The agent's default persona for the <em>normal chat flow</em> (no photo attached). When a photo
     * IS attached, {@link PhotoProcessMiddleware} short-circuits into crop mode instead.
     */
    private static final String DEFAULT_INSTRUCTIONS =
            "You are a helpful photography assistant. Give clear, practical advice on composition, "
                    + "lighting, gear, and editing. When the user attaches a photo it is automatically "
                    + "cropped for maximum impact, so focus your replies on the photography question.";

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    /** The provider chat client over the OpenAI Responses protocol. */
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

    /**
     * Durable memory as a framework {@link FoundryMemoryProvider}. The provider IS the whole feature:
     * the framework's {@code AIContextProvider} seam calls it to recall relevant facts before every
     * turn and to queue server-side extraction after — the app writes none of that orchestration.
     * Compare the without-framework project's hand-written {@code MemoryService} (~240 lines).
     */
    @Bean
    public FoundryMemoryProvider memoryProvider(
            ExecutorService agentExecutor,
            @Value("${FOUNDRY_PROJECT_ENDPOINT:}") String projectEndpoint,
            @Value("${MEMORY_STORE_NAME:memstore-photo}") String storeName) {
        FoundryClientFactory factory =
                new FoundryClientFactory(projectEndpoint, new DefaultAzureCredentialBuilder().build());
        FoundryMemoryClient memoryClient = factory.createMemoryClient(agentExecutor);
        return new FoundryMemoryProvider(memoryClient, storeName);
    }

    /** The agent: default model/tool loop, with the crop workflow attached as middleware. */
    @Bean
    public Agent hostedAgent(
            ChatClient chatClient,
            ObjectMapper objectMapper,
            ExecutorService agentExecutor,
            FoundryMemoryProvider memoryProvider,
            @Value("${MODEL:gpt-4o-mini}") String model,
            @Value("${AGENT_INSTRUCTIONS:}") String instructions) {
        String resolvedInstructions = (instructions == null || instructions.isBlank())
                ? DEFAULT_INSTRUCTIONS : instructions;
        return ChatClientAgent.builder(chatClient)
                .name("photo-process-agent")
                .instructions(resolvedInstructions)
                .chatOptions(ChatOptions.builder().modelId(model).build())
                .aiContextProvider(memoryProvider) // ← durable memory: one line, recall + store handled
                .middleware(new PhotoProcessMiddleware(chatClient, objectMapper, model, agentExecutor))
                .build();
    }

    @Bean
    public ConversationStore conversationStore() {
        return new InMemoryConversationStore();
    }

    @Bean
    public ResponseStore responseStore() {
        return new InMemoryResponseStore();
    }

    /** Adapts the agent to the Foundry Responses protocol (parsing, SSE, response object). */
    @Bean
    public ResponseHandler agentResponseHandler(
            Agent hostedAgent,
            ObjectMapper objectMapper,
            ConversationStore conversationStore,
            ResponseStore responseStore) {
        return new AgentResponseHandler(
                hostedAgent, objectMapper, java.time.Duration.ofMinutes(5),
                conversationStore, false, responseStore);
    }

    /** Serves {@code POST /responses}. */
    @Bean
    public ResponsesEndpoint responsesEndpoint(ResponseHandler agentResponseHandler) {
        return new ResponsesEndpoint(agentResponseHandler);
    }

    /** Serves the platform {@code /readiness} (and {@code /healthz}, {@code /}) probes. */
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
