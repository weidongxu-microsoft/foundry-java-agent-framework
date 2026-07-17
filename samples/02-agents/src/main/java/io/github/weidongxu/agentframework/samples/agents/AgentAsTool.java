package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.tool.AgentTool;
import io.github.weidongxu.agentframework.tool.AgentToolOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — use an agent as a tool.
 *
 * <p>{@link AgentTool#of} wraps an {@link Agent} as a {@code Tool} exposing a single {@code query}
 * parameter. A coordinator agent can then delegate sub-tasks to specialist agents, composing them
 * like functions.
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.AgentAsTool
 * }</pre>
 */
public final class AgentAsTool {

    private AgentAsTool() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent translator = ChatClientAgent.builder(chatClient)
                    .name("French Translator")
                    .instructions("Translate the user's text into natural French. Reply with only "
                            + "the translation.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .build();

            AgentTool translateTool = AgentTool.of(translator, AgentToolOptions.builder()
                    .name("translate_to_french")
                    .description("Translate a piece of English text into French.")
                    .build());

            Agent coordinator = ChatClientAgent.builder(chatClient)
                    .name("coordinator")
                    .instructions("You coordinate work. When French is needed, call the "
                            + "translate_to_french tool rather than translating yourself.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .tools(List.of(translateTool))
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "Say 'Good morning, welcome to the conference' in French.";
            System.out.println("Q: " + question);

            AgentResponse response = coordinator.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }
}
