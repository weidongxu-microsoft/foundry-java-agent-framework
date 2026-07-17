package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface Workflow {
    WorkflowRun start(
            List<ChatMessage> messages,
            WorkflowSession session);

    default CompletionStage<WorkflowRunResult> run(
            List<ChatMessage> messages,
            WorkflowSession session) {
        return start(messages, session).getResult();
    }
}
