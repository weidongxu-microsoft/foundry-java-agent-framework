package io.github.weidongxu.agentframework.samples.workflows;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;

import java.util.concurrent.Executor;

/**
 * Shared setup for the 03-workflows samples: read env, build a framework {@link ChatClient} over
 * the OpenAI Responses protocol, and compose named agents.
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

    static ChatClient chatClient(String apiKey, Executor executor) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey);
        String baseUrl = env("OPENAI_BASE_URL", null);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        OpenAIClient openai = builder.build();
        return new OpenAIResponsesChatClient(openai, executor);
    }

    static Agent agent(ChatClient chatClient, String name, String instructions) {
        return ChatClientAgent.builder(chatClient)
                .name(name)
                .instructions(instructions)
                .chatOptions(ChatOptions.builder().modelId(env("OPENAI_MODEL", "gpt-4o-mini")).build())
                .build();
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
