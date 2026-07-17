package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.tool.ToolApprovalBatch;
import io.github.weidongxu.agentframework.tool.ToolApprovalClaim;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryToolApprovalStoreTest {
    @Test
    void claimRequiresCompleteBatchAndMatchingScope() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryToolApprovalStore store =
                new InMemoryToolApprovalStore(clock, 10);
        ToolApprovalBatch batch = batch(
                "batch-1",
                "scope-1",
                "request-1",
                "request-2");
        store.create(batch).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThrows(
                Exception.class,
                () -> store.claim(
                                Collections.singleton("request-1"),
                                "scope-1",
                                Duration.ofMinutes(1))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertThrows(
                Exception.class,
                () -> store.claim(
                                batch.getCallsByRequestId().keySet(),
                                "scope-2",
                                Duration.ofMinutes(1))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
    }

    @Test
    void expiredLeaseUsesNewFencingTokenAndRejectsStaleCompletion()
            throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryToolApprovalStore store =
                new InMemoryToolApprovalStore(clock, 10);
        ToolApprovalBatch batch =
                batch("batch-1", "scope-1", "request-1");
        store.create(batch).toCompletableFuture().get(5, TimeUnit.SECONDS);
        ToolApprovalClaim first = store.claim(
                        Collections.singleton("request-1"),
                        "scope-1",
                        Duration.ofSeconds(30))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        clock.advance(Duration.ofSeconds(31));
        ToolApprovalClaim second = store.claim(
                        Collections.singleton("request-1"),
                        "scope-1",
                        Duration.ofSeconds(30))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(first.getFencingToken() + 1, second.getFencingToken());
        assertThrows(
                Exception.class,
                () -> store.complete(
                                batch.getId(),
                                first.getFencingToken())
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        store.complete(batch.getId(), second.getFencingToken())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThrows(
                Exception.class,
                () -> store.claim(
                                Collections.singleton("request-1"),
                                "scope-1",
                                Duration.ofSeconds(30))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
    }

    @Test
    void cleanupIsBounded() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryToolApprovalStore store =
                new InMemoryToolApprovalStore(clock, 10);
        store.create(batch("batch-1", null, "request-1"))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        store.create(batch("batch-2", null, "request-2"))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        clock.advance(Duration.ofHours(1));

        assertEquals(
                1,
                store.cleanup(clock.instant(), 1)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertEquals(
                1,
                store.cleanup(clock.instant(), 10)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
    }

    private static ToolApprovalBatch batch(
            String batchId,
            String scope,
            String... requestIds) {
        Map<String, FunctionCallContent> calls = new LinkedHashMap<>();
        for (String requestId : requestIds) {
            calls.put(
                    requestId,
                    new FunctionCallContent(
                            "call-" + requestId,
                            "echo",
                            "{}"));
        }
        return new ToolApprovalBatch(
                batchId,
                scope,
                calls,
                null,
                Collections.singletonList("echo"),
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2025-01-01T00:00:00Z");

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
