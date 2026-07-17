package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.compaction.CompactionProvider;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.impl.InMemoryChatHistoryProvider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — context compaction.
 *
 * <p>A {@link CompactionProvider} is an {@code AIContextProvider} that <em>replaces</em> the
 * assembled message list to keep the model's context bounded. Here host-managed history accumulates
 * across turns (via {@link InMemoryChatHistoryProvider}) while a sliding-window compactor keeps only
 * the most recent turns before each call.
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.Compaction
 * }</pre>
 */
public final class Compaction {

    private Compaction() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("compacting-agent")
                    .instructions("You are a concise assistant. Answer in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    // Host-managed history grows across turns...
                    .chatHistoryProvider(new InMemoryChatHistoryProvider())
                    // ...and the compactor keeps only the last 2 turns sent to the model.
                    .aiContextProvider(CompactionProvider.slidingWindow(2))
                    .build();

            AgentSession session = new AgentSession();
            System.out.println(ask(agent, session, "Let's talk about planets. What is Mercury?"));
            System.out.println(ask(agent, session, "And Venus?"));
            System.out.println(ask(agent, session, "And Earth?"));
            System.out.println(ask(agent, session, "And Mars?"));
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
