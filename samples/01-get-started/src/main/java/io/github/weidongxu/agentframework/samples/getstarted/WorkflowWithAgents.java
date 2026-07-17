package io.github.weidongxu.agentframework.samples.getstarted;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.workflow.SequentialWorkflow;
import io.github.weidongxu.agentframework.workflow.WorkflowRunResult;
import io.github.weidongxu.agentframework.workflow.WorkflowSession;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 01.5 — call agents inside a workflow.
 *
 * <p>A {@link SequentialWorkflow} chains agents: each participant runs in turn on the shared
 * conversation, and the next participant sees the previous one's output. Here a writer drafts a
 * blurb and an editor tightens it. See {@code samples/03-workflows} for concurrent, handoff, and
 * group-chat patterns.
 *
 * <pre>{@code
 *   mvn -q -f samples\01-get-started\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.getstarted.WorkflowWithAgents
 * }</pre>
 */
public final class WorkflowWithAgents {

    private WorkflowWithAgents() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent writer = agent(chatClient, "writer",
                    "You are a copywriter. Write a punchy two-sentence product blurb for the topic.");
            Agent editor = agent(chatClient, "editor",
                    "You are an editor. Tighten the previous blurb to a single vivid sentence.");

            SequentialWorkflow workflow = SequentialWorkflow.builder()
                    .participant("writer", writer)
                    .participant("editor", editor)
                    .build();

            String topic = args.length > 0
                    ? String.join(" ", args)
                    : "a reusable stainless-steel water bottle";
            System.out.println("Topic: " + topic);

            WorkflowRunResult result = workflow.run(
                            List.of(ChatMessage.user(topic)), new WorkflowSession())
                    .toCompletableFuture().get();

            List<ChatMessage> messages = result.getMessages();
            System.out.println("Final: " + messages.get(messages.size() - 1).getText());
        } finally {
            executor.shutdown();
        }
    }

    private static Agent agent(ChatClient chatClient, String name, String instructions) {
        return ChatClientAgent.builder(chatClient)
                .name(name)
                .instructions(instructions)
                .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                .build();
    }
}
