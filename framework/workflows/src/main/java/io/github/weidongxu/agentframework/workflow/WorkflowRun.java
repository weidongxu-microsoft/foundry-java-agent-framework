package io.github.weidongxu.agentframework.workflow;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

public final class WorkflowRun {
    private final String id;
    private final CompletableFuture<WorkflowRunResult> result;
    private final Runnable cancellation;
    private final AtomicReference<WorkflowStatus> status =
            new AtomicReference<>(WorkflowStatus.RUNNING);

    public WorkflowRun(
            String id,
            CompletableFuture<WorkflowRunResult> result,
            Runnable cancellation) {
        this.id = Objects.requireNonNull(id, "id");
        this.result = Objects.requireNonNull(result, "result");
        this.cancellation = Objects.requireNonNull(
                cancellation,
                "cancellation");
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                status.compareAndSet(
                        WorkflowStatus.RUNNING,
                        WorkflowStatus.CANCELLED);
            } else if (error != null) {
                status.compareAndSet(
                        WorkflowStatus.RUNNING,
                        WorkflowStatus.FAILED);
            } else {
                status.compareAndSet(
                        WorkflowStatus.RUNNING,
                        WorkflowStatus.COMPLETED);
            }
        });
    }

    public String getId() {
        return id;
    }

    public WorkflowStatus getStatus() {
        return status.get();
    }

    public CompletionStage<WorkflowRunResult> getResult() {
        return result;
    }

    public boolean cancel() {
        if (!status.compareAndSet(
                WorkflowStatus.RUNNING,
                WorkflowStatus.CANCELLED)) {
            return false;
        }
        boolean cancelled = result.cancel(true);
        cancellation.run();
        return cancelled;
    }
}
