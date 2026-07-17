package io.github.weidongxu.agentframework.samples.getstarted;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 01.4 — give the agent memory via a context provider.
 *
 * <p>A {@code FileMemoryProvider} is an {@code AIContextProvider}: it contributes memory tools and
 * injects recalled memories into each turn. Attach it with {@code aiContextProvider(...)}. Here the
 * agent stores a fact in the first turn and recalls it in the second. Memories persist as files
 * under a base directory, so they survive across process restarts.
 *
 * <pre>{@code
 *   mvn -q -f samples\01-get-started\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.getstarted.Memory
 * }</pre>
 */
public final class Memory {

    private Memory() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Path memoryDir = Files.createTempDirectory("sample-memory");
            FileMemoryProvider memoryProvider = new FileMemoryProvider(
                    FileMemoryProviderOptions.defaults().setBaseDirectory(memoryDir));
            System.out.println("Memories stored under: " + memoryDir);

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("memory-agent")
                    .instructions("You are a helpful assistant. Use your memory tools to remember "
                            + "durable facts the user shares, and recall them when relevant. Answer "
                            + "in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .aiContextProvider(memoryProvider)
                    .build();

            AgentSession session = new AgentSession();
            System.out.println(ask(agent, session, "Please remember that I prefer window seats."));
            System.out.println(ask(agent, session, "When you book my flight, which seat do I want?"));
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
}
