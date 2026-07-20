package com.example.hostedagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.foundry.FoundryClientFactory;
import io.github.weidongxu.agentframework.foundry.FoundryConfig;
import io.github.weidongxu.agentframework.foundry.FoundryMemoryClient;
import io.github.weidongxu.agentframework.foundry.FoundryMemoryProvider;
import io.github.weidongxu.agentframework.foundry.FoundryMemoryProviderOptions;
import io.github.weidongxu.agentframework.agentserver.foundry.AgentResponseHandler;
import io.github.weidongxu.agentframework.agentserver.foundry.FileSystemConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.ConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.InMemoryConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseHandler;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseLifecycleService;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseStore;
import io.github.weidongxu.agentframework.agentserver.responses.InMemoryResponseStore;
import io.github.weidongxu.agentframework.agentserver.spring.GracefulShutdown;
import io.github.weidongxu.agentframework.agentserver.spring.HealthController;
import io.github.weidongxu.agentframework.agentserver.spring.InFlightRequestTracker;
import io.github.weidongxu.agentframework.agentserver.spring.PlatformRequestFilter;
import io.github.weidongxu.agentframework.agentserver.spring.ResponsesEndpoint;
import io.github.weidongxu.agentframework.agentserver.spring.ResponseLifecycleEndpoint;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.langchain4j.LangChain4jChatClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.weidongxu.agentframework.mcp.McpToolSource;
import io.github.weidongxu.agentframework.skill.AgentSkillsProvider;
import io.github.weidongxu.agentframework.skill.FileSkillSource;
import io.github.weidongxu.agentframework.tool.HostedCodeInterpreterTool;
import io.github.weidongxu.agentframework.tool.HostedWebSearchTool;
import io.github.weidongxu.agentframework.tool.Tool;
import io.github.weidongxu.photo.RawDeveloper;
import io.github.weidongxu.photo.RawTherapeeDeveloper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.nio.file.Paths;

/**
 * Wires the hosted agent entirely on the Java Agent Framework — the parity gate proving the
 * framework replaces the container's hand-rolled Responses implementation.
 *
 * <p>Where the old container hand-coded the Responses endpoint, the tool loop, memory recall/store,
 * and per-user scoping, this configuration assembles those from framework pieces:</p>
 * <ul>
 *   <li>{@link FoundryClientFactory} builds the Foundry SDK clients and the OpenAI Responses
 *       {@link ChatClient};</li>
 *   <li>{@link ChatClientAgent} runs the model + tool loop with the hosted tools
 *       ({@link HostedWebSearchTool}, {@link HostedCodeInterpreterTool}), the local
 *       {@link TodoTool}, and — when enabled — read-only tools from a local
 *       {@link McpToolSource} (an external MCP server the framework connects to as a client),
 *       gated by a framework {@link FoundryMemoryProvider} whose salience (secrets, pleasantries)
 *       is delegated to the server-side memory extractor;</li>
 *   <li>the framework {@link ResponsesEndpoint} serves {@code POST /responses}, delegating to the
 *       Foundry {@link AgentResponseHandler} which derives the per-user {@code AgentSession} from
 *       the Foundry identity header.</li>
 * </ul>
 */
@Configuration
public class AgentConfiguration {

    private static final String DEFAULT_INSTRUCTIONS =
            "You are a helpful assistant. Use the web search tool for questions about current "
                    + "events or facts you are unsure of, and cite sources. Use the code interpreter "
                    + "tool to run Python for precise calculations, data analysis, or generating files. "
                    + "When the user attaches a camera RAW photo, it is developed to a JPEG for them "
                    + "automatically, with AI-suggested adjustments applied. Use the memories provided "
                    + "below to personalize your answers when relevant.";

    @Bean
    public FoundryClientFactory foundryClientFactory() {
        // FoundryConfig reads FOUNDRY_PROJECT_ENDPOINT and (optionally) FOUNDRY_CREDENTIAL /
        // FOUNDRY_MANAGED_IDENTITY_CLIENT_ID, selecting the credential without hardcoding it here.
        return FoundryConfig.fromEnvironment().createClientFactory();
    }

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors()));
    }

    @Bean
    public ChatClient chatClient(
            FoundryClientFactory factory,
            ExecutorService agentExecutor,
            @Value("${CHAT_CLIENT:foundry}") String chatClientKind,
            @Value("${LANGCHAIN4J_BASE_URL:https://api.openai.com/v1}") String langChain4jBaseUrl,
            @Value("${LANGCHAIN4J_API_KEY:}") String langChain4jApiKey,
            @Value("${LANGCHAIN4J_MODEL:${MODEL:gpt-5.4}}") String langChain4jModel) {
        // Opt-in ecosystem bridge: CHAT_CLIENT=langchain4j backs the framework's ChatClient with a
        // LangChain4j OpenAI-compatible model, demonstrating the langchain4j/ adapter end-to-end.
        // Default (foundry) is unchanged.
        if ("langchain4j".equalsIgnoreCase(chatClientKind.trim())) {
            return langChain4jChatClient(
                    langChain4jBaseUrl, langChain4jApiKey, langChain4jModel, agentExecutor);
        }
        // Supply the raw provider chat client. ChatClientAgent composes the default pipeline and
        // wraps it in a function-invoking decorator when one is not already present, so the model's
        // local function-tool calls (the todo tool) are executed in-process — no manual wrapping
        // needed in the workload.
        return factory.createChatClient(agentExecutor, agentExecutor);
    }

    private static ChatClient langChain4jChatClient(
            String baseUrl, String apiKey, String model, ExecutorService executor) {
        String key = (apiKey == null || apiKey.isBlank()) ? "not-set" : apiKey;
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .modelName(model)
                .build();
        StreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .modelName(model)
                .build();
        return new LangChain4jChatClient(chatModel, streamingChatModel, executor);
    }

    @Bean
    public FoundryMemoryClient foundryMemoryClient(
            FoundryClientFactory factory,
            ExecutorService agentExecutor) {
        return factory.createMemoryClient(agentExecutor);
    }

    @Bean
    public FoundryMemoryProvider memoryProvider(
            FoundryMemoryClient foundryMemoryClient,
            @Value("${MEMORY_STORE_NAME:memstore-hostagent}") String storeName,
            @Value("${MEMORY_SCOPE:demo-user}") String defaultScope,
            @Value("${MEMORY_UPDATE_DELAY_SECONDS:1}") int updateDelaySeconds,
            @Value("${MEMORY_MAX_RECALL:5}") int maxRecall) {
        // Salience (secrets, pleasantries, low-value turns) is delegated to the server-side memory
        // extractor, steered by the store's user_profile_details (set in admin Bootstrap) — matching
        // the .NET/Python providers, which ship no client-side gating. The only app-specific wiring
        // is the per-user scope, falling back to a default when there is no agent session.
        FoundryMemoryProviderOptions options = new FoundryMemoryProviderOptions()
                .setMaxMemories(maxRecall)
                .setUpdateDelaySeconds(updateDelaySeconds)
                .setScopeResolver(session ->
                        session != null && session.getId() != null && !session.getId().isBlank()
                                ? session.getId()
                                : defaultScope);
        return new FoundryMemoryProvider(foundryMemoryClient, storeName, options);
    }

    /**
     * Optional local-MCP tool source, enabled with {@code MCP_ENABLED=true}. The framework acts as
     * the MCP <em>client</em>: by default it spawns the read-only {@code mcp-server-git} over stdio
     * pointed at a repo baked into the image, and exposes only the seven read tools (allow-list) so
     * the model can never mutate the repo. Set {@code MCP_HTTP_URL} to connect to a streamable-HTTP
     * server instead. The bean owns the client/process and is closed on shutdown.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "MCP_ENABLED", havingValue = "true")
    public McpToolSource mcpToolSource(
            @Value("${MCP_HTTP_URL:}") String httpUrl,
            @Value("${MCP_STDIO_COMMAND:uvx}") String stdioCommand,
            @Value("${MCP_STDIO_ARGS:mcp-server-git,--repository,/opt/demo-repo}") String stdioArgs,
            @Value("${MCP_ALLOWED_TOOLS:git_status,git_log,git_show,git_diff,git_diff_staged,"
                    + "git_diff_unstaged,git_branch}") String allowedTools) {
        List<String> allowList = splitCsv(allowedTools);
        Collection<String> allowed = allowList.isEmpty() ? null : allowList;
        if (httpUrl != null && !httpUrl.isBlank()) {
            return McpToolSource.streamableHttp(httpUrl.trim(), allowed);
        }
        return McpToolSource.stdio(stdioCommand.trim(), splitCsv(stdioArgs), null, allowed);
    }

    /**
     * Optional Agent Skills provider, enabled with {@code SKILLS_ENABLED=true}. The framework's
     * {@link AgentSkillsProvider} advertises each baked skill's name/description as an
     * {@code <available_skills>} index and contributes the read-only {@code load_skill} /
     * {@code read_skill_resource} tools (progressive disclosure). Skills are discovered on the
     * filesystem under {@code SKILLS_DIR} (each subdirectory with a {@code SKILL.md} is one skill).
     */
    @Bean
    @ConditionalOnProperty(name = "SKILLS_ENABLED", havingValue = "true")
    public AgentSkillsProvider skillsProvider(
            @Value("${SKILLS_DIR:/opt/skills}") String skillsDir) {
        return new AgentSkillsProvider(new FileSkillSource(Paths.get(skillsDir.trim())));
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * The RAW→JPEG developer backing the photo pipeline. Uses the framework-independent
     * {@code raw-photo} library, which drives the native {@code rawtherapee-cli} (installed in the
     * runtime image). Configuration (CLI path, JPEG quality) comes from the library's env defaults.
     */
    @Bean
    public RawDeveloper rawDeveloper() {
        return new RawTherapeeDeveloper();
    }

    @Bean
    public Agent hostedAgent(
            ChatClient chatClient,
            FoundryMemoryProvider memoryProvider,
            TodoTool todoTool,
            RawDeveloper rawDeveloper,
            ObjectMapper objectMapper,
            ExecutorService agentExecutor,
            ObjectProvider<McpToolSource> mcpToolSource,
            ObjectProvider<AgentSkillsProvider> skillsProvider,
            @Value("${MODEL:gpt-5.4}") String model,
            @Value("${AGENT_INSTRUCTIONS:}") String instructions,
            @Value("${WEB_SEARCH_CONTEXT_SIZE:medium}") String searchContextSize,
            @Value("${CODE_INTERPRETER_ENABLED:true}") boolean codeInterpreterEnabled,
            @Value("${TODO_TOOL_ENABLED:false}") boolean todoToolEnabled,
            @Value("${PHOTO_ENABLED:true}") boolean photoEnabled,
            @Value("${PHOTO_MAX_LONG_EDGE_PX:0}") int photoMaxLongEdgePx,
            @Value("${PHOTO_ADVICE_ENABLED:true}") boolean photoAdviceEnabled,
            @Value("${PHOTO_ADVICE_LONG_EDGE_PX:1024}") int photoAdviceLongEdgePx,
            @Value("${PHOTO_LENS_CORRECTION:true}") boolean photoLensCorrection,
            @Value("${MIDDLEWARE_ENABLED:true}") boolean middlewareEnabled) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new HostedWebSearchTool(
                HostedWebSearchTool.SearchContextSize.valueOf(
                        searchContextSize.trim().toUpperCase(Locale.ROOT))));
        if (codeInterpreterEnabled) {
            tools.add(new HostedCodeInterpreterTool());
        }
        if (todoToolEnabled) {
            tools.add(todoTool.asFunctionTool());
        }
        // When enabled, add the local MCP server's (read-only) tools alongside the app's own tools.
        McpToolSource mcp = mcpToolSource.getIfAvailable();
        if (mcp != null) {
            tools.addAll(mcp.listTools());
        }
        String resolvedInstructions = (instructions == null || instructions.isBlank())
                ? DEFAULT_INSTRUCTIONS
                : instructions;
        ChatClientAgent.Builder agentBuilder = ChatClientAgent.builder(chatClient)
                .instructions(resolvedInstructions)
                .chatOptions(ChatOptions.builder().modelId(model).build())
                .tools(tools)
                .aiContextProvider(memoryProvider);
        // Demonstrate the framework middleware extension point end-to-end: MarkerMiddleware wraps
        // every run and (only on an opt-in MW_PING sentinel) mutates the request so the reply is
        // observably marked, letting the client assert middleware actually executed in-container.
        if (middlewareEnabled) {
            agentBuilder.middleware(new MarkerMiddleware());
        }
        // The app-owned RAW-photo pipeline: when a user attaches a camera RAW, develop it to a JPEG.
        // With advice enabled, a small neutral preview is shown to the vision model, which returns
        // adjustment values, and the RAW is re-developed adjusted; otherwise a neutral JPEG is
        // returned (item #1). Either way it short-circuits the model. Replaces the demo TodoTool.
        if (photoEnabled) {
            agentBuilder.middleware(new RawDevelopMiddleware(
                    rawDeveloper,
                    photoMaxLongEdgePx > 0 ? photoMaxLongEdgePx : null,
                    agentExecutor,
                    chatClient,
                    objectMapper,
                    model,
                    photoAdviceEnabled,
                    photoAdviceLongEdgePx > 0 ? photoAdviceLongEdgePx : null,
                    photoLensCorrection));
        }
        // When enabled, add Agent Skills as a second context provider (progressive disclosure).
        AgentSkillsProvider skills = skillsProvider.getIfAvailable();
        if (skills != null) {
            agentBuilder.aiContextProvider(skills);
        }
        return agentBuilder.build();
    }

    /**
     * Selects the host-owned conversation history store. Default {@code memory} matches the
     * AgentServer SDK parity default (.NET {@code InMemoryResponsesProvider} / Python
     * {@code InMemoryResponseProvider}). Set {@code CONVERSATION_STORE=file} to persist history under
     * {@code $HOME/.checkpoints/conversations} — durable across process restarts within a Foundry
     * session (single replica); use a distributed backend for multi-instance.
     */
    @Bean
    public ConversationStore conversationStore(
            ObjectMapper objectMapper,
            @Value("${agent-framework.conversation-store:${CONVERSATION_STORE:memory}}") String kind) {
        if ("file".equalsIgnoreCase(kind)) {
            return new FileSystemConversationStore(objectMapper);
        }
        return new InMemoryConversationStore();
    }

    @Bean
    public ResponseStore responseStore() {
        return new InMemoryResponseStore();
    }

    @Bean
    public ResponseHandler agentResponseHandler(
            Agent hostedAgent,
            ObjectMapper objectMapper,
            ConversationStore conversationStore,
            ResponseStore responseStore,
            @Value("${CHAT_CLIENT:foundry}") String chatClientKind,
            @Value("${MODEL:gpt-5.4}") String model) {
        // Stamp the active chat-client backend + model onto every response's metadata so an
        // end-to-end caller (e.g. the client workload) can tell which ChatClient served the turn —
        // making the langchain4j bridge distinguishable from the default foundry path.
        String backend = "langchain4j".equalsIgnoreCase(chatClientKind.trim())
                ? "langchain4j" : "foundry";
        return new AgentResponseHandler(
                hostedAgent, objectMapper, Duration.ofMinutes(5), conversationStore, false,
                responseStore)
                .withResponseMetadata(Map.of(
                        "chat_client", backend,
                        "chat_model", model));
    }

    @Bean
    public ResponsesEndpoint responsesEndpoint(ResponseHandler agentResponseHandler) {
        return new ResponsesEndpoint(agentResponseHandler);
    }

    @Bean
    public ResponseLifecycleService responseLifecycleService(ResponseStore responseStore) {
        return new ResponseLifecycleService(responseStore);
    }

    @Bean
    public ResponseLifecycleEndpoint responseLifecycleEndpoint(
            ResponseLifecycleService responseLifecycleService) {
        return new ResponseLifecycleEndpoint(responseLifecycleService);
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
