package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.tool.ToolApprovalBatch;
import io.github.weidongxu.agentframework.tool.ToolApprovalClaim;
import io.github.weidongxu.agentframework.tool.ToolApprovalStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class InMemoryToolApprovalStore implements ToolApprovalStore {
    public static final int DEFAULT_MAX_PENDING_REQUESTS = 1024;

    private final Clock clock;
    private final int maxPendingRequests;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> batchIdsByRequestId =
            new LinkedHashMap<>();

    public InMemoryToolApprovalStore() {
        this(Clock.systemUTC(), DEFAULT_MAX_PENDING_REQUESTS);
    }

    public InMemoryToolApprovalStore(
            Clock clock,
            int maxPendingRequests) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPendingRequests <= 0) {
            throw new IllegalArgumentException(
                    "maxPendingRequests must be positive");
        }
        this.maxPendingRequests = maxPendingRequests;
    }

    @Override
    public synchronized CompletionStage<Void> create(
            ToolApprovalBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (entries.containsKey(batch.getId())) {
            return failed(new IllegalStateException(
                    "Tool approval batch already exists: " + batch.getId()));
        }
        if (batchIdsByRequestId.size()
                + batch.getCallsByRequestId().size()
                > maxPendingRequests) {
            return failed(new IllegalStateException(
                    "Too many pending tool approval requests"));
        }
        for (String requestId : batch.getCallsByRequestId().keySet()) {
            if (batchIdsByRequestId.containsKey(requestId)) {
                return failed(new IllegalStateException(
                        "Tool approval request already exists: " + requestId));
            }
        }
        entries.put(batch.getId(), new Entry(batch));
        batch.getCallsByRequestId().keySet().forEach(requestId ->
                batchIdsByRequestId.put(requestId, batch.getId()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<ToolApprovalClaim> claim(
            Set<String> requestIds,
            String scope,
            Duration leaseDuration) {
        Objects.requireNonNull(requestIds, "requestIds");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (requestIds.isEmpty()) {
            return failed(new IllegalArgumentException(
                    "requestIds cannot be empty"));
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            return failed(new IllegalArgumentException(
                    "leaseDuration must be positive"));
        }
        String batchId = batchIdsByRequestId.get(
                requestIds.iterator().next());
        Entry entry = batchId == null ? null : entries.get(batchId);
        if (entry == null || entry.completed || entry.abandoned) {
            return failed(unknown(requestIds.iterator().next()));
        }
        if (!entry.batch.getCallsByRequestId().keySet().equals(requestIds)) {
            return failed(new IllegalStateException(
                    "Tool approval responses must resolve the complete request batch"));
        }
        if (!Objects.equals(entry.batch.getScope(), scope)) {
            return failed(new IllegalStateException(
                    "Tool approval response does not match its scope"));
        }
        Instant now = clock.instant();
        if (entry.leaseExpiresAt != null
                && entry.leaseExpiresAt.isAfter(now)) {
            return failed(new IllegalStateException(
                    "Tool approval batch is already claimed"));
        }
        entry.fencingToken++;
        entry.leaseExpiresAt = now.plus(leaseDuration);
        return CompletableFuture.completedFuture(new ToolApprovalClaim(
                entry.batch,
                entry.fencingToken,
                entry.leaseExpiresAt));
    }

    @Override
    public synchronized CompletionStage<Void> complete(
            String batchId,
            long fencingToken) {
        Entry entry = requiredClaim(batchId, fencingToken);
        if (entry == null) {
            return failed(new IllegalStateException(
                    "Tool approval claim is stale or expired"));
        }
        entry.completed = true;
        entry.leaseExpiresAt = null;
        removeRequestIndexes(entry.batch);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> release(
            String batchId,
            long fencingToken) {
        Entry entry = requiredClaim(batchId, fencingToken);
        if (entry == null) {
            return failed(new IllegalStateException(
                    "Tool approval claim is stale or expired"));
        }
        entry.leaseExpiresAt = null;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> abandon(
            Set<String> requestIds) {
        Objects.requireNonNull(requestIds, "requestIds");
        if (requestIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Set<String> batchIds = new HashSet<>();
        requestIds.forEach(requestId -> {
            String batchId = batchIdsByRequestId.get(requestId);
            if (batchId != null) {
                batchIds.add(batchId);
            }
        });
        for (String batchId : batchIds) {
            Entry entry = entries.get(batchId);
            if (entry != null && !entry.completed) {
                if (entry.leaseExpiresAt != null
                        && entry.leaseExpiresAt.isAfter(clock.instant())) {
                    return failed(new IllegalStateException(
                            "Claimed tool approval batch cannot be abandoned"));
                }
                entry.abandoned = true;
                entry.leaseExpiresAt = null;
                removeRequestIndexes(entry.batch);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Integer> cleanup(
            Instant createdBefore,
            int maxItems) {
        Objects.requireNonNull(createdBefore, "createdBefore");
        if (maxItems <= 0) {
            return failed(new IllegalArgumentException(
                    "maxItems must be positive"));
        }
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (removed.size() >= maxItems) {
                break;
            }
            Entry entry = item.getValue();
            if (entry.batch.getCreatedAt().isBefore(createdBefore)
                    && (entry.completed
                            || entry.abandoned
                            || entry.leaseExpiresAt == null
                            || !entry.leaseExpiresAt.isAfter(clock.instant()))) {
                removed.add(item.getKey());
                removeRequestIndexes(entry.batch);
            }
        }
        removed.forEach(entries::remove);
        return CompletableFuture.completedFuture(removed.size());
    }

    private Entry requiredClaim(String batchId, long fencingToken) {
        Entry entry = entries.get(Objects.requireNonNull(batchId, "batchId"));
        Instant now = clock.instant();
        if (entry == null
                || entry.completed
                || entry.abandoned
                || entry.fencingToken != fencingToken
                || entry.leaseExpiresAt == null
                || !entry.leaseExpiresAt.isAfter(now)) {
            return null;
        }
        return entry;
    }

    private void removeRequestIndexes(ToolApprovalBatch batch) {
        batch.getCallsByRequestId().keySet().forEach(
                batchIdsByRequestId::remove);
    }

    private static IllegalStateException unknown(String requestId) {
        return new IllegalStateException(
                "Unknown or already resolved tool approval request: "
                        + requestId);
    }

    private static <T> CompletionStage<T> failed(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
    }

    private static final class Entry {
        private final ToolApprovalBatch batch;
        private long fencingToken;
        private Instant leaseExpiresAt;
        private boolean completed;
        private boolean abandoned;

        private Entry(ToolApprovalBatch batch) {
            this.batch = batch;
        }
    }
}
