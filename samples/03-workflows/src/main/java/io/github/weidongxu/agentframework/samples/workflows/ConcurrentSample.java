package io.github.weidongxu.agentframework.samples.workflows;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.workflow.ConcurrentWorkflow;
import io.github.weidongxu.agentframework.workflow.WorkflowRunResult;
import io.github.weidongxu.agentframework.workflow.WorkflowSession;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 03 — concurrent workflow.
 *
 * <p>All participants run in parallel on the same input; their responses are collected together.
 * Here three reviewers critique a proposal from different angles at once.
 *
 * <pre>{@code
 *   mvn -q -f samples\03-workflows\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.workflows.ConcurrentSample
 * }</pre>
 */
public final class ConcurrentSample {

    private ConcurrentSample() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent security = Support.agent(chatClient, "security",
                    "Review the proposal for security risks only. One sentence.");
            Agent cost = Support.agent(chatClient, "cost",
                    "Review the proposal for cost concerns only. One sentence.");
            Agent ux = Support.agent(chatClient, "ux",
                    "Review the proposal for user-experience impact only. One sentence.");

            ConcurrentWorkflow workflow = ConcurrentWorkflow.builder()
                    .participant("security", security)
                    .participant("cost", cost)
                    .participant("ux", ux)
                    .build();

            String proposal = args.length > 0
                    ? String.join(" ", args)
                    : "We will store user session tokens in browser local storage.";
            System.out.println("Proposal: " + proposal);

            WorkflowRunResult result = workflow.run(
                            List.of(ChatMessage.user(proposal)), new WorkflowSession())
                    .toCompletableFuture().get();

            System.out.println("Reviews:");
            for (ChatMessage message : result.getMessages()) {
                System.out.println("  - " + message.getText());
            }
        } finally {
            executor.shutdown();
        }
    }
}
