package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agent.ChatHistoryProvider;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatMessageJsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link ChatHistoryProvider}: persists each session's chat history as a JSON file under
 * a base directory (default {@code $HOME/.checkpoints/chat-history}), keyed by {@link AgentSession#getId()}.
 *
 * <p>The file-system analog of {@link InMemoryChatHistoryProvider}: history survives process/pod
 * restarts within a session because the file outlives the in-memory session map. It mirrors the
 * external-store chat-history providers in the .NET/Python framework (Cosmos/Valkey), using the
 * local disk as a single-instance durability tier. It is <b>not</b> shared across replicas — for
 * multi-instance durability use a distributed backend.</p>
 *
 * <p><strong>Security:</strong> stored history may contain PII/sensitive content, and messages are
 * loaded as-is with no validation. Protect the directory, and only trust history from a store you
 * control — a tampered file could inject adversarial content into the agent's context.</p>
 */
public final class FileChatHistoryProvider extends ChatHistoryProvider {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /** Uses {@code $HOME/.checkpoints/chat-history} as the base directory. */
    public FileChatHistoryProvider(ObjectMapper objectMapper) {
        this(defaultBaseDir(), objectMapper);
    }

    public FileChatHistoryProvider(Path baseDir, ObjectMapper objectMapper) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private static Path defaultBaseDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            home = ".";
        }
        return Paths.get(home, ".checkpoints", "chat-history");
    }

    /** Returns the persisted history for a session (empty when none/unreadable). */
    public List<ChatMessage> getMessages(AgentSession session) {
        if (session == null) {
            return Collections.emptyList();
        }
        synchronized (lockFor(session.getId())) {
            return load(session.getId());
        }
    }

    /** Replaces the persisted history for a session. */
    public void setMessages(AgentSession session, List<? extends ChatMessage> messages) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(messages, "messages");
        synchronized (lockFor(session.getId())) {
            save(session.getId(), new ArrayList<>(messages));
        }
    }

    @Override
    protected CompletionStage<List<ChatMessage>> provide(AgentInvokingContext context) {
        return CompletableFuture.completedFuture(getMessages(context.getSession()));
    }

    @Override
    protected CompletionStage<Void> store(AgentInvokedContext context) {
        AgentSession session = context.getSession();
        if (session != null) {
            synchronized (lockFor(session.getId())) {
                List<ChatMessage> messages = new ArrayList<>(load(session.getId()));
                messages.addAll(context.getRequestMessages());
                messages.addAll(context.getResponseMessages());
                save(session.getId(), messages);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private Object lockFor(String sessionId) {
        return locks.computeIfAbsent(sessionId, key -> new Object());
    }

    private List<ChatMessage> load(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.isRegularFile(file)) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(file));
            return ChatMessageJsonCodec.readMessages(root);
        } catch (IOException | RuntimeException error) {
            return Collections.emptyList();
        }
    }

    private void save(String sessionId, List<ChatMessage> history) {
        Path file = fileFor(sessionId);
        try {
            Files.createDirectories(file.getParent());
            ArrayNode root = ChatMessageJsonCodec.writeMessages(objectMapper, history);
            byte[] bytes = objectMapper.writeValueAsBytes(root);
            // Atomic write: stage to a temp sibling, then move into place so a concurrent reader (or
            // a crash mid-write) never observes a partial file.
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            // Best-effort persistence: a failed write must not break the invocation.
        }
    }

    private Path fileFor(String sessionId) {
        return baseDir.resolve(fileName(sessionId));
    }

    /**
     * Maps an opaque session id to a safe, collision-free file name: a sanitized prefix for
     * readability plus a hash suffix that disambiguates ids differing only in stripped characters.
     */
    private static String fileName(String sessionId) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < sessionId.length() && safe.length() < 64; i++) {
            char c = sessionId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            safe.append(ok ? c : '_');
        }
        return safe + "-" + sha256Hex(sessionId).substring(0, 16) + ".json";
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
}
