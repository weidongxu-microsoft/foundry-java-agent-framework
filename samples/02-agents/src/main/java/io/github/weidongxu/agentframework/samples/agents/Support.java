package io.github.weidongxu.agentframework.samples.agents;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;

import java.util.concurrent.Executor;

/**
 * Shared setup for the 02-agents samples: read env and build a framework {@link ChatClient} over
 * the OpenAI Responses protocol (OpenAI or any OpenAI-compatible endpoint).
 *
 * <p>Env: {@code OPENAI_API_KEY} (required), {@code OPENAI_MODEL} (default {@code gpt-4o-mini}),
 * {@code OPENAI_BASE_URL} (optional).
 */
final class Support {

    private Support() {
    }

    static String requireApiKey() {
        String apiKey = env("OPENAI_API_KEY", null);
        if (apiKey == null) {
            System.err.println("ERROR: set OPENAI_API_KEY (an OpenAI or OpenAI-compatible key).");
            System.exit(2);
        }
        return apiKey;
    }

    static String model() {
        return env("OPENAI_MODEL", "gpt-4o-mini");
    }

    static ChatClient chatClient(String apiKey, Executor executor) {
        return new OpenAIResponsesChatClient(openAiClient(apiKey), executor);
    }

    static OpenAIClient openAiClient(String apiKey) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey);
        String baseUrl = env("OPENAI_BASE_URL", null);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
