package io.github.weidongxu.agentframework.samples.getstarted;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 01.3 — multi-turn conversation.
 *
 * <p>Reuse a single {@link AgentSession} across two {@code run} calls. The session carries the
 * service-managed conversation forward, so the second turn sees the context of the first — the
 * model remembers what you told it.
 *
 * <pre>{@code
 *   mvn -q -f samples\01-get-started\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.getstarted.MultiTurn
 * }</pre>
 */
public final class MultiTurn {

    private MultiTurn() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);
            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("chat-agent")
                    .instructions("You are a concise assistant. Answer in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .build();

            // A single session threads both turns together.
            AgentSession session = new AgentSession();

            System.out.println(ask(agent, session, "My name is Ada and I love the Java language."));
            System.out.println(ask(agent, session, "What is my name, and what do I love?"));
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
