package io.github.weidongxu.agentframework.samples.agents;

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
 * Sample 02 — an agent with multiple function tools.
 *
 * <p>Register several {@link FunctionTool}s; the model picks which to call. The framework runs the
 * tool loop and returns the final answer.
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.FunctionTools
 * }</pre>
 */
public final class FunctionTools {

    private FunctionTools() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            FunctionTool add = new FunctionTool(
                    "add",
                    "Add two integers and return the sum.",
                    twoNumberSchema(),
                    arguments -> CompletableFuture.completedFuture(
                            String.valueOf(asLong(arguments, "a") + asLong(arguments, "b"))));

            FunctionTool multiply = new FunctionTool(
                    "multiply",
                    "Multiply two integers and return the product.",
                    twoNumberSchema(),
                    arguments -> CompletableFuture.completedFuture(
                            String.valueOf(asLong(arguments, "a") * asLong(arguments, "b"))));

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("calculator-agent")
                    .instructions("You are a precise calculator. Use the add and multiply tools; "
                            + "never do arithmetic yourself. State the final number in one sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .tools(List.of(add, multiply))
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "What is (12 + 8) multiplied by 5?";
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }

    private static Map<String, Object> twoNumberSchema() {
        Map<String, Object> number = new LinkedHashMap<>();
        number.put("type", "integer");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("a", number);
        properties.put("b", number);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("a", "b"));
        return schema;
    }

    private static long asLong(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }
}
