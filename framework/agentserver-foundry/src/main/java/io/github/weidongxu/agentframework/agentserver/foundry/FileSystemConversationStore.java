package io.github.weidongxu.agentframework.agentserver.foundry;

import io.github.weidongxu.agentframework.agentserver.responses.ConversationStore;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatMessageJsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * File-backed {@link ConversationStore} that persists per-session chat history as JSON files under a
 * base directory (default {@code $HOME/.checkpoints/conversations}).
 *
 * <p>Purpose: survive process/pod restarts within a Foundry session. The hosted-agent container's
 * {@code $HOME} (e.g. {@code /home/session}) is a session-durable mount that Foundry preserves across
 * requests and restarts for the lifetime of a session, so writing history there lets a redeployed or
 * restarted replica resume threading. It is <b>not</b> shared across replicas — a single-instance
 * durability tier, the file-system analog of the SDK's in-memory default. For multi-instance or
 * cross-session durability use a distributed backend (Cosmos/Redis/SQL) implementing
 * {@link ConversationStore}.</p>
 *
 * <p>Note on parity: the open-source AgentServer SDK (.NET/Python) defaults conversation storage to
 * <i>in-memory</i>, so {@code InMemoryConversationStore} remains the framework default. This class is
 * an opt-in durable alternative (distinct from MAF's workflow-checkpoint {@code AgentSessionStore},
 * which also uses {@code $HOME/.checkpoints}).</p>
 */
public final class FileSystemConversationStore implements ConversationStore {
    private static final Logger LOG = LoggerFactory.getLogger(FileSystemConversationStore.class);

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    /** Uses {@code $HOME/.checkpoints/conversations} as the base directory. */
    public FileSystemConversationStore(ObjectMapper objectMapper) {
        this(defaultBaseDir(), objectMapper);
    }

    public FileSystemConversationStore(Path baseDir, ObjectMapper objectMapper) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private static Path defaultBaseDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            home = ".";
        }
        return Paths.get(home, ".checkpoints", "conversations");
    }

    @Override
    public List<ChatMessage> load(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        Path file = fileFor(sessionKey);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isArray()) {
                return null;
            }
            return ChatMessageJsonCodec.readMessages(root);
        } catch (IOException | RuntimeException error) {
            LOG.warn("filesystem-store: failed to load history for key={} ({}); starting fresh",
                    sessionKey, error.toString());
            return null;
        }
    }

    @Override
    public void save(String sessionKey, List<ChatMessage> history) {
        if (sessionKey == null || history == null) {
            return;
        }
        Path file = fileFor(sessionKey);
        try {
            Files.createDirectories(file.getParent());
            ArrayNode root = ChatMessageJsonCodec.writeMessages(objectMapper, history);
            byte[] bytes = objectMapper.writeValueAsBytes(root);
            // Atomic write: stage to a temp file in the same directory, then move into place so a
            // concurrent reader (or a crash mid-write) never observes a partial file.
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            LOG.warn("filesystem-store: failed to save history for key={} ({})",
                    sessionKey, error.toString());
        }
    }

    private Path fileFor(String sessionKey) {
        return baseDir.resolve(fileName(sessionKey));
    }

    /**
     * Maps an opaque session key (e.g. {@code conv_*} / {@code resp_*}, or an arbitrary
     * gateway-supplied id) to a safe, collision-free file name: a sanitized prefix for readability
     * plus a hash suffix that disambiguates keys differing only in stripped characters.
     */
    private static String fileName(String sessionKey) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < sessionKey.length() && safe.length() < 64; i++) {
            char c = sessionKey.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            safe.append(ok ? c : '_');
        }
        return safe + "-" + sha256Hex(sessionKey).substring(0, 16) + ".json";
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
