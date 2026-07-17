package io.github.weidongxu.agentframework.samples.getstarted;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.ToolHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 01.2 — give the agent a function tool.
 *
 * <p>Build a {@link FunctionTool} from a name, description, a JSON-schema-shaped parameter map, and
 * a {@link ToolHandler}. {@link ChatClientAgent} runs the model/tool loop: when the model asks to
 * call {@code get_weather}, the framework invokes the handler in-process and feeds the result back.
 *
 * <pre>{@code
 *   mvn -q -f samples\01-get-started\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.getstarted.AddTools
 * }</pre>
 */
public final class AddTools {

    private AddTools() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            FunctionTool weatherTool = new FunctionTool(
                    "get_weather",
                    "Get the current weather for a city.",
                    weatherSchema(),
                    weatherHandler());

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("weather-agent")
                    .instructions("You are a helpful assistant. Use the get_weather tool when asked "
                            + "about the weather, then answer in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .tools(List.of(weatherTool))
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "What's the weather like in Seattle right now?";
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }

    private static Map<String, Object> weatherSchema() {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");
        city.put("description", "City name, e.g. \"Seattle\".");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", city);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("city"));
        return schema;
    }

    private static ToolHandler weatherHandler() {
        // A real tool would call a weather API; here we return canned data so the sample is offline.
        return arguments -> {
            String city = String.valueOf(arguments.getOrDefault("city", "unknown"));
            String json = "{\"city\":\"" + city + "\",\"tempC\":21,\"conditions\":\"partly cloudy\"}";
            return CompletableFuture.completedFuture(json);
        };
    }
}
