package io.github.weidongxu.agentframework.samples.agents;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.langchain4j.LangChain4jChatClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — swap the chat-client backend.
 *
 * <p>The agent programs against the framework's {@link ChatClient} abstraction, so the backend is
 * interchangeable. This sample builds the same agent over either the OpenAI Responses client (the
 * default in every other sample) or the LangChain4j bridge — selected with {@code CHAT_CLIENT}.
 *
 * <ul>
 *   <li>{@code CHAT_CLIENT=openai} (default) → {@code OpenAIResponsesChatClient}</li>
 *   <li>{@code CHAT_CLIENT=langchain4j} → {@link LangChain4jChatClient} over an OpenAI-compatible
 *       model ({@code OPENAI_BASE_URL} default {@code https://api.openai.com/v1})</li>
 * </ul>
 *
 * <pre>{@code
 *   $env:CHAT_CLIENT = "langchain4j"
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.ChatClients
 * }</pre>
 */
public final class ChatClients {

    private ChatClients() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        String kind = Support.env("CHAT_CLIENT", "openai");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = "langchain4j".equalsIgnoreCase(kind)
                    ? langChain4jChatClient(apiKey, executor)
                    : Support.chatClient(apiKey, executor);
            System.out.println("Backend: " + kind);

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("portable-agent")
                    .instructions("You are a concise assistant. Answer in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "In one sentence, what is an abstraction layer?";
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }

    private static ChatClient langChain4jChatClient(String apiKey, ExecutorService executor) {
        String baseUrl = Support.env("OPENAI_BASE_URL", "https://api.openai.com/v1");
        String model = Support.model();
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model).build();
        StreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model).build();
        return new LangChain4jChatClient(chatModel, streamingChatModel, executor);
    }
}
