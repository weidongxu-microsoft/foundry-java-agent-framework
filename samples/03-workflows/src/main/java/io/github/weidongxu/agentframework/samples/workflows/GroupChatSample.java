package io.github.weidongxu.agentframework.samples.workflows;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.workflow.GroupChatWorkflow;
import io.github.weidongxu.agentframework.workflow.WorkflowRunResult;
import io.github.weidongxu.agentframework.workflow.WorkflowSession;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sample 03 — group-chat workflow.
 *
 * <p>A manager function decides which participant speaks each round; a termination policy ends the
 * chat. Here a brainstormer and a critic alternate: the manager picks brainstormer first, then
 * critic, and the chat ends once the critic has spoken.
 *
 * <pre>{@code
 *   mvn -q -f samples\03-workflows\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.workflows.GroupChatSample
 * }</pre>
 */
public final class GroupChatSample {

    private GroupChatSample() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent brainstormer = Support.agent(chatClient, "brainstormer",
                    "Propose one bold idea for the topic in a single sentence.");
            Agent critic = Support.agent(chatClient, "critic",
                    "Critique the latest idea in a single constructive sentence.");

            AtomicInteger turn = new AtomicInteger();
            GroupChatWorkflow workflow = GroupChatWorkflow.builder()
                    .participant("brainstormer", brainstormer)
                    .participant("critic", critic)
                    .manager((messages, participants, round) -> CompletableFuture.completedFuture(
                            turn.getAndIncrement() == 0 ? "brainstormer" : "critic"))
                    .terminationPolicy(context -> "critic".equals(context.getLastParticipant()))
                    .maxRounds(4)
                    .build();

            String topic = args.length > 0 ? String.join(" ", args) : "reducing food waste at home";
            System.out.println("Topic: " + topic);

            WorkflowRunResult result = workflow.run(
                            List.of(ChatMessage.user(topic)), new WorkflowSession())
                    .toCompletableFuture().get();

            System.out.println("Transcript:");
            for (ChatMessage message : result.getMessages()) {
                System.out.println("  - " + message.getText());
            }
        } finally {
            executor.shutdown();
        }
    }
}
