package io.github.weidongxu.agentframework.samples.workflows;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.workflow.HandoffWorkflow;
import io.github.weidongxu.agentframework.workflow.WorkflowRunResult;
import io.github.weidongxu.agentframework.workflow.WorkflowSession;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 03 — handoff workflow.
 *
 * <p>An initial participant handles the request and can transfer control to another participant.
 * The framework injects a {@code transfer_to_<participant>} tool for each peer; the model calls it
 * to hand off. Here a triage agent routes billing questions to a billing specialist.
 *
 * <pre>{@code
 *   mvn -q -f samples\03-workflows\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.workflows.HandoffSample
 * }</pre>
 */
public final class HandoffSample {

    private HandoffSample() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent triage = Support.agent(chatClient, "triage",
                    "You triage support requests. If the request is about billing or payments, call "
                            + "the transfer_to_billing tool to hand off. Otherwise answer briefly.");
            Agent billing = Support.agent(chatClient, "billing",
                    "You are a billing specialist. Resolve the customer's billing question clearly.");

            HandoffWorkflow workflow = HandoffWorkflow.builder()
                    .id("support")
                    .participant("triage", triage)
                    .participant("billing", billing)
                    .initialParticipant("triage")
                    .build();

            String request = args.length > 0
                    ? String.join(" ", args)
                    : "I was charged twice for my subscription this month. Can you help?";
            System.out.println("Request: " + request);

            WorkflowRunResult result = workflow.run(
                            List.of(ChatMessage.user(request)), new WorkflowSession())
                    .toCompletableFuture().get();

            List<ChatMessage> messages = result.getMessages();
            System.out.println("Resolution: " + messages.get(messages.size() - 1).getText());
        } finally {
            executor.shutdown();
        }
    }
}
