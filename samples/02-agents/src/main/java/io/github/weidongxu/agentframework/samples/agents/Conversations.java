package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.impl.InMemoryChatHistoryProvider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — host-managed conversations (threads).
 *
 * <p>With a {@link InMemoryChatHistoryProvider} the <em>host</em> owns conversation history, keyed
 * by {@link AgentSession}. Each session is an independent thread: two sessions on the same agent do
 * not see each other's messages. (For durable, restart-surviving threads use
 * {@code FileChatHistoryProvider}.)
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.Conversations
 * }</pre>
 */
public final class Conversations {

    private Conversations() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("threaded-agent")
                    .instructions("You are a concise assistant. Answer in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .chatHistoryProvider(new InMemoryChatHistoryProvider())
                    .build();

            // Thread A remembers within itself.
            AgentSession threadA = new AgentSession();
            System.out.println("[A] " + ask(agent, threadA, "My favorite color is teal."));
            System.out.println("[A] " + ask(agent, threadA, "What is my favorite color?"));

            // Thread B is a separate conversation — it has no knowledge of thread A.
            AgentSession threadB = new AgentSession();
            System.out.println("[B] " + ask(agent, threadB, "Do you know my favorite color?"));
        } finally {
            executor.shutdown();
        }
    }

    private static String ask(Agent agent, AgentSession session, String text) throws Exception {
        AgentResponse response = agent.run(
                        List.of(ChatMessage.user(text)), session, AgentRunOptions.empty())
                .toCompletableFuture().get();
        return "Q: " + text + " | A: " + response.getText();
    }
}
