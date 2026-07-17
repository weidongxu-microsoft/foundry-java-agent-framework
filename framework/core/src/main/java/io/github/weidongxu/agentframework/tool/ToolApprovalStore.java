package io.github.weidongxu.agentframework.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public interface ToolApprovalStore {
    /**
     * Creates a pending batch atomically.
     */
    CompletionStage<Void> create(ToolApprovalBatch batch);

    /**
     * Claims the complete batch and returns a lease with a fencing token.
     */
    CompletionStage<ToolApprovalClaim> claim(
            Set<String> requestIds,
            String scope,
            Duration leaseDuration);

    CompletionStage<Void> complete(String batchId, long fencingToken);

    CompletionStage<Void> release(String batchId, long fencingToken);

    /**
     * Removes requests that were never delivered to the caller.
     */
    CompletionStage<Void> abandon(Set<String> requestIds);

    /**
     * Removes at most {@code maxItems} old terminal, unclaimed, or expired batches.
     */
    CompletionStage<Integer> cleanup(Instant createdBefore, int maxItems);
}
