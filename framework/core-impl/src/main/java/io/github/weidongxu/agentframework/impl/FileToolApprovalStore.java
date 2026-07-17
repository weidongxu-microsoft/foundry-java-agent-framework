package io.github.weidongxu.agentframework.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import io.github.weidongxu.agentframework.tool.ToolApprovalBatch;
import io.github.weidongxu.agentframework.tool.ToolApprovalClaim;
import io.github.weidongxu.agentframework.tool.ToolApprovalStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A durable, file-backed {@link ToolApprovalStore} that mirrors the fencing-token / lease semantics
 * of {@link InMemoryToolApprovalStore} but persists each pending batch to disk so approvals survive a
 * process/pod restart.
 *
 * <p>Each batch is written atomically (staged temp file + atomic move) to a JSON file under
 * {@code baseDir}; the in-memory index is rebuilt from those files at construction. This is a
 * <b>single-instance</b> durability tier — the filesystem analog of the in-memory default — intended
 * for a session-durable mount (e.g. a Foundry hosted-agent {@code $HOME}). It is not shared across
 * replicas; for multi-instance or cross-process durability use a distributed backend (Cosmos / Redis
 * / SQL) implementing {@link ToolApprovalStore}.</p>
 */
public final class FileToolApprovalStore implements ToolApprovalStore {
    public static final int DEFAULT_MAX_PENDING_REQUESTS = 1024;

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxPendingRequests;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> batchIdsByRequestId = new LinkedHashMap<>();

    public FileToolApprovalStore(Path baseDir) {
        this(baseDir, new ObjectMapper(), Clock.systemUTC(), DEFAULT_MAX_PENDING_REQUESTS);
    }

    public FileToolApprovalStore(
            Path baseDir, ObjectMapper objectMapper, Clock clock, int maxPendingRequests) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPendingRequests <= 0) {
            throw new IllegalArgumentException("maxPendingRequests must be positive");
        }
        this.maxPendingRequests = maxPendingRequests;
        loadFromDisk();
    }

    @Override
    public synchronized CompletionStage<Void> create(ToolApprovalBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (entries.containsKey(batch.getId())) {
            return failed(new IllegalStateException(
                    "Tool approval batch already exists: " + batch.getId()));
        }
        if (batchIdsByRequestId.size() + batch.getCallsByRequestId().size() > maxPendingRequests) {
            return failed(new IllegalStateException("Too many pending tool approval requests"));
        }
        for (String requestId : batch.getCallsByRequestId().keySet()) {
            if (batchIdsByRequestId.containsKey(requestId)) {
                return failed(new IllegalStateException(
                        "Tool approval request already exists: " + requestId));
            }
        }
        Entry entry = new Entry(batch);
        entries.put(batch.getId(), entry);
        batch.getCallsByRequestId().keySet().forEach(
                requestId -> batchIdsByRequestId.put(requestId, batch.getId()));
        persist(entry);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<ToolApprovalClaim> claim(
            Set<String> requestIds, String scope, Duration leaseDuration) {
        Objects.requireNonNull(requestIds, "requestIds");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (requestIds.isEmpty()) {
            return failed(new IllegalArgumentException("requestIds cannot be empty"));
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            return failed(new IllegalArgumentException("leaseDuration must be positive"));
        }
        String batchId = batchIdsByRequestId.get(requestIds.iterator().next());
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
        if (entry.leaseExpiresAt != null && entry.leaseExpiresAt.isAfter(now)) {
            return failed(new IllegalStateException("Tool approval batch is already claimed"));
        }
        entry.fencingToken++;
        entry.leaseExpiresAt = now.plus(leaseDuration);
        persist(entry);
        return CompletableFuture.completedFuture(
                new ToolApprovalClaim(entry.batch, entry.fencingToken, entry.leaseExpiresAt));
    }

    @Override
    public synchronized CompletionStage<Void> complete(String batchId, long fencingToken) {
        Entry entry = requiredClaim(batchId, fencingToken);
        if (entry == null) {
            return failed(new IllegalStateException("Tool approval claim is stale or expired"));
        }
        entry.completed = true;
        entry.leaseExpiresAt = null;
        removeRequestIndexes(entry.batch);
        persist(entry);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> release(String batchId, long fencingToken) {
        Entry entry = requiredClaim(batchId, fencingToken);
        if (entry == null) {
            return failed(new IllegalStateException("Tool approval claim is stale or expired"));
        }
        entry.leaseExpiresAt = null;
        persist(entry);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> abandon(Set<String> requestIds) {
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
                if (entry.leaseExpiresAt != null && entry.leaseExpiresAt.isAfter(clock.instant())) {
                    return failed(new IllegalStateException(
                            "Claimed tool approval batch cannot be abandoned"));
                }
                entry.abandoned = true;
                entry.leaseExpiresAt = null;
                removeRequestIndexes(entry.batch);
                persist(entry);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Integer> cleanup(Instant createdBefore, int maxItems) {
        Objects.requireNonNull(createdBefore, "createdBefore");
        if (maxItems <= 0) {
            return failed(new IllegalArgumentException("maxItems must be positive"));
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
        for (String batchId : removed) {
            entries.remove(batchId);
            deleteFile(batchId);
        }
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
        batch.getCallsByRequestId().keySet().forEach(batchIdsByRequestId::remove);
    }

    private static IllegalStateException unknown(String requestId) {
        return new IllegalStateException(
                "Unknown or already resolved tool approval request: " + requestId);
    }

    private static <T> CompletionStage<T> failed(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
    }

    // --- persistence ---------------------------------------------------------------------------

    private void loadFromDisk() {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        List<Entry> loaded = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "*.json")) {
            for (Path file : stream) {
                Entry entry = readEntry(file);
                if (entry != null) {
                    loaded.add(entry);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("failed to load tool approval store from " + baseDir, error);
        }
        loaded.sort((a, b) -> a.batch.getCreatedAt().compareTo(b.batch.getCreatedAt()));
        for (Entry entry : loaded) {
            entries.put(entry.batch.getId(), entry);
            if (!entry.completed && !entry.abandoned) {
                entry.batch.getCallsByRequestId().keySet().forEach(
                        requestId -> batchIdsByRequestId.put(requestId, entry.batch.getId()));
            }
        }
    }

    private void persist(Entry entry) {
        try {
            Files.createDirectories(baseDir);
            byte[] bytes = objectMapper.writeValueAsBytes(writeEntry(entry));
            Path file = fileFor(entry.batch.getId());
            Path temp = Files.createTempFile(baseDir, "approval-", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("failed to persist tool approval batch " + entry.batch.getId(), error);
        }
    }

    private void deleteFile(String batchId) {
        try {
            Files.deleteIfExists(fileFor(batchId));
        } catch (IOException error) {
            throw new UncheckedIOException("failed to delete tool approval batch " + batchId, error);
        }
    }

    private Path fileFor(String batchId) {
        return baseDir.resolve(sha256Hex(batchId).substring(0, 32) + ".json");
    }

    // --- codec: Entry <-> JSON -----------------------------------------------------------------

    private ObjectNode writeEntry(Entry entry) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("fencingToken", entry.fencingToken);
        if (entry.leaseExpiresAt != null) {
            node.put("leaseExpiresAt", entry.leaseExpiresAt.toString());
        }
        node.put("completed", entry.completed);
        node.put("abandoned", entry.abandoned);
        node.set("batch", writeBatch(entry.batch));
        return node;
    }

    private Entry readEntry(Path file) {
        try {
            JsonNode node = objectMapper.readTree(Files.readAllBytes(file));
            if (node == null || !node.hasNonNull("batch")) {
                return null;
            }
            ToolApprovalBatch batch = readBatch(node.get("batch"));
            if (batch == null) {
                return null;
            }
            Entry entry = new Entry(batch);
            entry.fencingToken = node.path("fencingToken").asLong(0);
            entry.completed = node.path("completed").asBoolean(false);
            entry.abandoned = node.path("abandoned").asBoolean(false);
            if (node.hasNonNull("leaseExpiresAt")) {
                entry.leaseExpiresAt = Instant.parse(node.get("leaseExpiresAt").asText());
            }
            return entry;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private ObjectNode writeBatch(ToolApprovalBatch batch) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", batch.getId());
        if (batch.getScope() != null) {
            node.put("scope", batch.getScope());
        }
        node.put("createdAt", batch.getCreatedAt().toString());
        ArrayNode toolNames = node.putArray("toolNames");
        batch.getToolNames().forEach(toolNames::add);
        ArrayNode calls = node.putArray("calls");
        batch.getCallsByRequestId().forEach((requestId, call) -> {
            ObjectNode callNode = calls.addObject();
            callNode.put("requestId", requestId);
            callNode.put("callId", call.getCallId());
            callNode.put("name", call.getName());
            callNode.put("arguments", call.getArguments());
        });
        if (batch.getResumeConversation() != null) {
            ArrayNode conversation = node.putArray("resumeConversation");
            for (ChatMessage message : batch.getResumeConversation()) {
                conversation.add(writeMessage(message));
            }
        }
        return node;
    }

    private ToolApprovalBatch readBatch(JsonNode node) {
        if (node == null || !node.hasNonNull("id")) {
            return null;
        }
        Map<String, FunctionCallContent> calls = new LinkedHashMap<>();
        JsonNode callsNode = node.get("calls");
        if (callsNode != null && callsNode.isArray()) {
            for (JsonNode callNode : callsNode) {
                calls.put(callNode.path("requestId").asText(""),
                        new FunctionCallContent(
                                callNode.path("callId").asText(""),
                                callNode.path("name").asText(""),
                                callNode.path("arguments").asText("")));
            }
        }
        List<String> toolNames = new ArrayList<>();
        JsonNode toolNamesNode = node.get("toolNames");
        if (toolNamesNode != null && toolNamesNode.isArray()) {
            toolNamesNode.forEach(name -> toolNames.add(name.asText()));
        }
        List<ChatMessage> resumeConversation = null;
        JsonNode conversationNode = node.get("resumeConversation");
        if (conversationNode != null && conversationNode.isArray()) {
            resumeConversation = new ArrayList<>();
            for (JsonNode messageNode : conversationNode) {
                ChatMessage message = readMessage(messageNode);
                if (message != null) {
                    resumeConversation.add(message);
                }
            }
        }
        return new ToolApprovalBatch(
                node.get("id").asText(),
                node.hasNonNull("scope") ? node.get("scope").asText() : null,
                calls,
                resumeConversation,
                toolNames,
                Instant.parse(node.path("createdAt").asText()));
    }

    private ObjectNode writeMessage(ChatMessage message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", message.getRole().name());
        if (message.getAuthorName() != null) {
            node.put("authorName", message.getAuthorName());
        }
        ArrayNode contents = node.putArray("contents");
        for (ChatContent content : message.getContents()) {
            ObjectNode contentNode = writeContent(content);
            if (contentNode != null) {
                contents.add(contentNode);
            }
        }
        return node;
    }

    private ObjectNode writeContent(ChatContent content) {
        ObjectNode node = objectMapper.createObjectNode();
        if (content instanceof TextContent) {
            node.put("type", "text");
            node.put("text", ((TextContent) content).getText());
        } else if (content instanceof FunctionCallContent) {
            FunctionCallContent call = (FunctionCallContent) content;
            node.put("type", "function_call");
            node.put("callId", call.getCallId());
            node.put("name", call.getName());
            node.put("arguments", call.getArguments());
        } else if (content instanceof FunctionResultContent) {
            FunctionResultContent result = (FunctionResultContent) content;
            node.put("type", "function_result");
            node.put("callId", result.getCallId());
            node.put("result", result.getResult());
            node.put("error", result.isError());
        } else if (content instanceof ToolApprovalRequestContent) {
            ToolApprovalRequestContent request = (ToolApprovalRequestContent) content;
            FunctionCallContent call = request.getFunctionCall();
            node.put("type", "tool_approval_request");
            node.put("requestId", request.getRequestId());
            ObjectNode callNode = node.putObject("functionCall");
            callNode.put("callId", call.getCallId());
            callNode.put("name", call.getName());
            callNode.put("arguments", call.getArguments());
        } else if (content instanceof ToolApprovalResponseContent) {
            ToolApprovalResponseContent response = (ToolApprovalResponseContent) content;
            node.put("type", "tool_approval_response");
            node.put("requestId", response.getRequestId());
            node.put("approved", response.isApproved());
            if (response.getReason() != null) {
                node.put("reason", response.getReason());
            }
        } else {
            return null;
        }
        return node;
    }

    private ChatMessage readMessage(JsonNode node) {
        if (node == null || !node.hasNonNull("role")) {
            return null;
        }
        ChatRole role;
        try {
            role = ChatRole.valueOf(node.get("role").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownRole) {
            return null;
        }
        ChatMessage.Builder builder = ChatMessage.builder(role);
        if (node.hasNonNull("authorName")) {
            builder.authorName(node.get("authorName").asText());
        }
        JsonNode contents = node.get("contents");
        if (contents != null && contents.isArray()) {
            for (JsonNode contentNode : contents) {
                ChatContent content = readContent(contentNode);
                if (content != null) {
                    builder.addContent(content);
                }
            }
        }
        return builder.build();
    }

    private ChatContent readContent(JsonNode node) {
        if (node == null || !node.hasNonNull("type")) {
            return null;
        }
        switch (node.get("type").asText()) {
            case "text":
                return new TextContent(node.path("text").asText(""));
            case "function_call":
                return new FunctionCallContent(
                        node.path("callId").asText(""),
                        node.path("name").asText(""),
                        node.path("arguments").asText(""));
            case "function_result":
                return new FunctionResultContent(
                        node.path("callId").asText(""),
                        node.path("result").asText(""),
                        node.path("error").asBoolean(false));
            case "tool_approval_request": {
                JsonNode call = node.path("functionCall");
                return new ToolApprovalRequestContent(
                        node.path("requestId").asText(""),
                        new FunctionCallContent(
                                call.path("callId").asText(""),
                                call.path("name").asText(""),
                                call.path("arguments").asText("")));
            }
            case "tool_approval_response":
                return new ToolApprovalResponseContent(
                        node.path("requestId").asText(""),
                        node.path("approved").asBoolean(false),
                        node.hasNonNull("reason") ? node.get("reason").asText() : null);
            default:
                return null;
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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
