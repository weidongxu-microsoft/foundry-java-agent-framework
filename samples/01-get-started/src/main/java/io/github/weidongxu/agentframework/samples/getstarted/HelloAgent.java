package io.github.weidongxu.agentframework.samples.getstarted;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 01 — create and run your first agent.
 *
 * <p>The minimal path through the framework: wrap an OpenAI Responses client in the framework's
 * {@link ChatClient}, compose an {@link Agent} with {@link ChatClientAgent}, and run one turn.
 *
 * <p>Run against OpenAI (or any OpenAI-compatible endpoint):
 * <pre>{@code
 *   $env:OPENAI_API_KEY = "sk-..."
 *   # optional: $env:OPENAI_MODEL = "gpt-4o-mini"   (default below)
 *   # optional: $env:OPENAI_BASE_URL = "https://<compatible-endpoint>/v1"
 *   mvn -q compile exec:java
 *   # or pass your own prompt:
 *   mvn -q compile exec:java "-Dexec.args=Explain records in one sentence."
 * }</pre>
 */
public final class HelloAgent {

    private HelloAgent() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = env("OPENAI_API_KEY", null);
        if (apiKey == null) {
            System.err.println("ERROR: set OPENAI_API_KEY (an OpenAI or OpenAI-compatible key).");
            System.exit(2);
            return;
        }
        String model = env("OPENAI_MODEL", "gpt-4o-mini");
        String baseUrl = env("OPENAI_BASE_URL", null);

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder().apiKey(apiKey);
        if (baseUrl != null) {
            clientBuilder.baseUrl(baseUrl);
        }
        OpenAIClient openai = clientBuilder.build();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 1. Framework ChatClient over the OpenAI Responses protocol.
            ChatClient chatClient = new OpenAIResponsesChatClient(openai, executor);

            // 2. Compose an agent: instructions + model. ChatClientAgent runs the model/tool loop.
            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("hello-agent")
                    .instructions("You are a friendly assistant. Answer in a single short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(model).build())
                    .build();

            // 3. Run one turn.
            String question = args.length > 0
                    ? String.join(" ", args)
                    : "Say hello, then share one fun fact about the Java programming language.";
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
