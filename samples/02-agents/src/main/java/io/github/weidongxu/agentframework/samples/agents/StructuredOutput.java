package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ResponseFormat;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — structured (JSON) output.
 *
 * <p>Set a {@link ResponseFormat#jsonSchema} on {@link ChatOptions} so the model returns JSON that
 * conforms to your schema, ready to deserialize into a typed object.
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.StructuredOutput
 * }</pre>
 */
public final class StructuredOutput {

    private StructuredOutput() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            ResponseFormat format = ResponseFormat.jsonSchema("person", personSchema());

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("extractor-agent")
                    .instructions("Extract structured data about a person from the user's text.")
                    .chatOptions(ChatOptions.builder()
                            .modelId(Support.model())
                            .responseFormat(format)
                            .build())
                    .build();

            String text = args.length > 0
                    ? String.join(" ", args)
                    : "Ada Lovelace was a 36-year-old mathematician from London.";
            System.out.println("Input: " + text);

            AgentResponse response = agent.run(text).toCompletableFuture().get();
            System.out.println("JSON: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }

    private static Map<String, Object> personSchema() {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        Map<String, Object> age = new LinkedHashMap<>();
        age.put("type", "integer");
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("age", age);
        properties.put("city", city);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "age", "city"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
