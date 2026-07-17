package io.github.weidongxu.agentframework.samples.workflows;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.workflow.SequentialWorkflow;
import io.github.weidongxu.agentframework.workflow.WorkflowRunResult;
import io.github.weidongxu.agentframework.workflow.WorkflowSession;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 03 — sequential workflow.
 *
 * <p>Agents run one after another on a shared conversation; each sees the previous output. Here an
 * outliner drafts bullet points and a writer expands them into a paragraph.
 *
 * <pre>{@code
 *   mvn -q -f samples\03-workflows\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.workflows.SequentialSample
 * }</pre>
 */
public final class SequentialSample {

    private SequentialSample() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent outliner = Support.agent(chatClient, "outliner",
                    "Produce a terse 3-bullet outline for the user's topic.");
            Agent writer = Support.agent(chatClient, "writer",
                    "Expand the previous outline into a single tight paragraph.");

            SequentialWorkflow workflow = SequentialWorkflow.builder()
                    .participant("outliner", outliner)
                    .participant("writer", writer)
                    .build();

            String topic = args.length > 0 ? String.join(" ", args) : "why unit tests matter";
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
}
