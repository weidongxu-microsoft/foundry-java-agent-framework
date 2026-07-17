package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.tool.ToolApprovalBatch;
import io.github.weidongxu.agentframework.tool.ToolApprovalClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolApprovalStoreTest {

    @TempDir
    Path tempDir;

    private FileToolApprovalStore store(Clock clock) {
        return new FileToolApprovalStore(tempDir, new ObjectMapper(), clock, 10);
    }

    @Test
    void claimRequiresCompleteBatchAndMatchingScope() throws Exception {
        FileToolApprovalStore store = store(new MutableClock());
        ToolApprovalBatch batch = batch("batch-1", "scope-1", "request-1", "request-2");
        store.create(batch).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThrows(Exception.class, () -> store.claim(
                Collections.singleton("request-1"), "scope-1", Duration.ofMinutes(1))
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertThrows(Exception.class, () -> store.claim(
                batch.getCallsByRequestId().keySet(), "scope-2", Duration.ofMinutes(1))
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void expiredLeaseUsesNewFencingTokenAndRejectsStaleCompletion() throws Exception {
        MutableClock clock = new MutableClock();
        FileToolApprovalStore store = store(clock);
        ToolApprovalBatch batch = batch("batch-1", "scope-1", "request-1");
        store.create(batch).toCompletableFuture().get(5, TimeUnit.SECONDS);
        ToolApprovalClaim first = store.claim(
                Collections.singleton("request-1"), "scope-1", Duration.ofSeconds(30))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        clock.advance(Duration.ofSeconds(31));
        ToolApprovalClaim second = store.claim(
                Collections.singleton("request-1"), "scope-1", Duration.ofSeconds(30))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(first.getFencingToken() + 1, second.getFencingToken());
        assertThrows(Exception.class, () -> store.complete(batch.getId(), first.getFencingToken())
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
        store.complete(batch.getId(), second.getFencingToken())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void cleanupIsBoundedAndDeletesFiles() throws Exception {
        MutableClock clock = new MutableClock();
        FileToolApprovalStore store = store(clock);
        store.create(batch("batch-1", null, "request-1")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        store.create(batch("batch-2", null, "request-2")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        clock.advance(Duration.ofHours(1));

        assertEquals(1, store.cleanup(clock.instant(), 1).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(1, store.cleanup(clock.instant(), 10).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(0, store.cleanup(clock.instant(), 10).toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void pendingBatchSurvivesRestart() throws Exception {
        MutableClock clock = new MutableClock();
        ToolApprovalBatch batch = batchWithConversation("batch-1", "scope-1", "request-1");
        FileToolApprovalStore first = store(clock);
        first.create(batch).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Simulate a process restart: a fresh store instance over the same directory.
        FileToolApprovalStore reopened = store(clock);
        ToolApprovalClaim claim = reopened.claim(
                Collections.singleton("request-1"), "scope-1", Duration.ofMinutes(1))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        // The resume conversation round-trips through disk with full fidelity.
        List<ChatMessage> resume = claim.getBatch().getResumeConversation();
        assertEquals(1, resume.size());
        assertEquals("please continue", resume.get(0).getText());
        assertEquals(1, claim.getBatch().getToolNames().size());

        reopened.complete(batch.getId(), claim.getFencingToken())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        // After completion, a further restart no longer offers the request for claiming.
        FileToolApprovalStore afterComplete = store(clock);
        assertThrows(Exception.class, () -> afterComplete.claim(
                Collections.singleton("request-1"), "scope-1", Duration.ofMinutes(1))
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void createRejectsDuplicateBatch() throws Exception {
        FileToolApprovalStore store = store(new MutableClock());
        store.create(batch("batch-1", null, "request-1")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThrows(Exception.class, () -> store.create(batch("batch-1", null, "request-2"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void abandonedRequestIsRemovedFromIndexAcrossRestart() throws Exception {
        MutableClock clock = new MutableClock();
        FileToolApprovalStore store = store(clock);
        store.create(batch("batch-1", "scope-1", "request-1"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        store.abandon(Collections.singleton("request-1")).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FileToolApprovalStore reopened = store(clock);
        assertThrows(Exception.class, () -> reopened.claim(
                Collections.singleton("request-1"), "scope-1", Duration.ofMinutes(1))
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void toolNamesAreRoundTripped() throws Exception {
        FileToolApprovalStore store = store(new MutableClock());
        store.create(batch("batch-1", "scope-1", "request-1"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        FileToolApprovalStore reopened = store(new MutableClock());
        ToolApprovalClaim claim = reopened.claim(
                Collections.singleton("request-1"), "scope-1", Duration.ofMinutes(1))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(claim.getBatch().getToolNames().contains("echo"));
    }

    private static ToolApprovalBatch batch(String batchId, String scope, String... requestIds) {
        Map<String, FunctionCallContent> calls = new LinkedHashMap<>();
        for (String requestId : requestIds) {
            calls.put(requestId, new FunctionCallContent("call-" + requestId, "echo", "{}"));
        }
        return new ToolApprovalBatch(batchId, scope, calls, null,
                Collections.singletonList("echo"), Instant.parse("2025-01-01T00:00:00Z"));
    }

    private static ToolApprovalBatch batchWithConversation(String batchId, String scope, String requestId) {
        Map<String, FunctionCallContent> calls = new LinkedHashMap<>();
        FunctionCallContent call = new FunctionCallContent("call-" + requestId, "echo", "{\"x\":1}");
        calls.put(requestId, call);
        ChatMessage message = ChatMessage.builder(io.github.weidongxu.agentframework.chat.ChatRole.USER)
                .addContent(new TextContent("please continue"))
                .addContent(new ToolApprovalRequestContent(requestId, call))
                .build();
        return new ToolApprovalBatch(batchId, scope, calls,
                Collections.singletonList(message),
                Collections.singletonList("echo"), Instant.parse("2025-01-01T00:00:00Z"));
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
