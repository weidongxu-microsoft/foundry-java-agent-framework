package io.github.weidongxu.agentframework.samples.e2e;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.harness.FileMemoryProvider;
import io.github.weidongxu.agentframework.harness.FileMemoryProviderOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.ToolHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 05 — end-to-end scenario combining the core building blocks in one agent:
 * <ul>
 *   <li>a function tool ({@code book_flight}) the agent calls to act;</li>
 *   <li>memory ({@code FileMemoryProvider}) so preferences persist across turns;</li>
 *   <li>a multi-turn {@link AgentSession} threading the conversation.</li>
 * </ul>
 *
 * <p>Turn 1 the user states a seat preference (stored in memory); turn 2 they ask to book a flight,
 * and the agent recalls the preference and calls the tool. For a <em>deployed</em> end-to-end
 * (a hosted service called over the wire), see the {@code app/} host and {@code client/} workloads.
 *
 * <pre>{@code
 *   $env:OPENAI_API_KEY = "sk-..."
 *   mvn -q -f samples\05-end-to-end\pom.xml compile exec:java
 * }</pre>
 */
public final class TravelAssistant {

    private TravelAssistant() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = env("OPENAI_API_KEY", null);
        if (apiKey == null) {
            System.err.println("ERROR: set OPENAI_API_KEY (an OpenAI or OpenAI-compatible key).");
            System.exit(2);
            return;
        }
        String model = env("OPENAI_MODEL", "gpt-4o-mini");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = chatClient(apiKey, executor);

            Path memoryDir = Files.createTempDirectory("sample-e2e-memory");
            FileMemoryProvider memory = new FileMemoryProvider(
                    FileMemoryProviderOptions.defaults().setBaseDirectory(memoryDir));

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("travel-assistant")
                    .instructions("You are a travel assistant. Remember the traveler's durable "
                            + "preferences using your memory tools, and apply them. When asked to "
                            + "book, call the book_flight tool. Answer in one or two sentences.")
                    .chatOptions(ChatOptions.builder().modelId(model).build())
                    .tools(List.of(bookFlightTool()))
                    .aiContextProvider(memory)
                    .build();

            AgentSession session = new AgentSession();
            System.out.println(ask(agent, session,
                    "For future trips, remember I always prefer a window seat."));
            System.out.println(ask(agent, session,
                    "Book me a flight from Seattle to Tokyo next Friday."));
        } finally {
            executor.shutdown();
        }
    }

    private static String ask(Agent agent, AgentSession session, String text) throws Exception {
        System.out.println("Q: " + text);
        AgentResponse response = agent.run(
                        List.of(ChatMessage.user(text)), session, AgentRunOptions.empty())
                .toCompletableFuture().get();
        return "A: " + response.getText();
    }

    private static FunctionTool bookFlightTool() {
        Map<String, Object> string = new LinkedHashMap<>();
        string.put("type", "string");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("origin", string);
        properties.put("destination", string);
        properties.put("date", string);
        properties.put("seat", string);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("origin", "destination", "date"));

        ToolHandler handler = arguments -> {
            String confirmation = "BOOKED " + arguments.getOrDefault("origin", "?")
                    + "->" + arguments.getOrDefault("destination", "?")
                    + " on " + arguments.getOrDefault("date", "?")
                    + ", seat=" + arguments.getOrDefault("seat", "unspecified")
                    + ", ref=ABC123";
            return CompletableFuture.completedFuture(confirmation);
        };
        return new FunctionTool("book_flight", "Book a flight for the traveler.", schema, handler);
    }

    private static ChatClient chatClient(String apiKey, ExecutorService executor) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey);
        String baseUrl = env("OPENAI_BASE_URL", null);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        OpenAIClient openai = builder.build();
        return new OpenAIResponsesChatClient(openai, executor);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
