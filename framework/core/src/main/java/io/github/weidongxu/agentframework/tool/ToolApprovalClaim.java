package io.github.weidongxu.agentframework.tool;

import java.time.Instant;
import java.util.Objects;

public final class ToolApprovalClaim {
    private final ToolApprovalBatch batch;
    private final long fencingToken;
    private final Instant leaseExpiresAt;

    public ToolApprovalClaim(
            ToolApprovalBatch batch,
            long fencingToken,
            Instant leaseExpiresAt) {
        this.batch = Objects.requireNonNull(batch, "batch");
        if (fencingToken <= 0) {
            throw new IllegalArgumentException(
                    "fencingToken must be positive");
        }
        this.fencingToken = fencingToken;
        this.leaseExpiresAt = Objects.requireNonNull(
                leaseExpiresAt,
                "leaseExpiresAt");
    }

    public ToolApprovalBatch getBatch() {
        return batch;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }
}
