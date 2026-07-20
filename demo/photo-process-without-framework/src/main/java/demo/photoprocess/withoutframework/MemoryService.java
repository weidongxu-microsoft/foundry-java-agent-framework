package demo.photoprocess.withoutframework;

import com.azure.ai.agents.MemoryStoresClient;
import com.azure.ai.agents.models.MemoryItem;
import com.azure.ai.agents.models.MemorySearchItem;
import com.azure.ai.agents.models.MemorySearchOptions;
import com.azure.ai.agents.models.MemoryStoreSearchResponse;
import com.openai.models.responses.ResponseInputItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hand-written durable-memory orchestration — the WITHOUT-framework counterpart to attaching a
 * {@code FoundryMemoryProvider} in one line. There is no provider seam here, so the container itself
 * drives the Foundry memory store around every model turn:
 *
 * <ul>
 *   <li>{@link #recall} — searches the store <em>before</em> the turn and returns relevant durable
 *       facts to splice into the system instructions, and</li>
 *   <li>{@link #remember} — starts an asynchronous update <em>after</em> the turn so the store can
 *       extract new durable facts from the exchange.</li>
 * </ul>
 *
 * <p>None of this is transport plumbing you could skip — it is the salience/scoping/safety policy the
 * framework's provider owns for you: per-user scope resolution (the low-level memory API does not
 * resolve the caller's identity), a secret guard (durable memory offers no redaction), a pleasantry
 * filter (so the server-side extractor doesn't append an empty item per trivial turn), a recall
 * char-budget, and the explicit {@code type:"message"} shape the memory data-plane parser requires.</p>
 */
final class MemoryService {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryService.class);

    /** Pleasantries that almost never carry a durable fact — skipping them avoids extractor bloat. */
    private static final Set<String> PLEASANTRIES = Set.of(
            "hi", "hello", "hey", "yo", "thanks", "thank you", "thx", "ty", "ok", "okay", "k",
            "yes", "yep", "yeah", "no", "nope", "sure", "cool", "nice", "great", "got it", "gotcha",
            "bye", "goodbye", "cheers", "np", "lol");

    /** Coarse secret detectors: a match in either side suppresses the whole write (no redaction). */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),                                  // AWS access key id
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),                        // GitHub token
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\."),    // JWT / bearer
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),                      // Slack token
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|client[_-]?secret|access[_-]?token)"
                    + "\\s*[:=]\\s*\\S{6,}"));                                    // key/secret = value

    private final MemoryStoresClient memoryStores;
    private final String storeName;
    private final String defaultScope;
    private final int updateDelaySeconds;

    // read-side gating knobs
    private final int maxRecall;
    private final int recallCharBudget;
    private final Set<String> excludeKinds;

    // write-side gating knobs
    private final boolean secretGuard;
    private final int minInputChars;
    private final int maxItemChars;

    MemoryService(
            MemoryStoresClient memoryStores,
            String storeName,
            String defaultScope,
            int updateDelaySeconds,
            int maxRecall,
            int recallCharBudget,
            String excludeKinds,
            boolean secretGuard,
            int minInputChars,
            int maxItemChars) {
        this.memoryStores = memoryStores;
        this.storeName = storeName;
        this.defaultScope = defaultScope;
        this.updateDelaySeconds = updateDelaySeconds;
        this.maxRecall = maxRecall;
        this.recallCharBudget = recallCharBudget;
        this.excludeKinds = Arrays.stream((excludeKinds == null ? "" : excludeKinds).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.secretGuard = secretGuard;
        this.minInputChars = minInputChars;
        this.maxItemChars = maxItemChars;
    }

    /**
     * Resolves the effective memory scope: the caller-supplied {@code scope} when present, else the
     * configured default. The low-level memory APIs do not auto-resolve the end user's identity (only
     * the hosted memory search tool does), so a trusted caller must assert the per-user scope.
     */
    String effectiveScope(String scope) {
        return (scope != null && !scope.isBlank()) ? scope.trim() : defaultScope;
    }

    /**
     * Searches the store for facts relevant to the user's input and returns them joined by newlines,
     * ready to splice into the system instructions. Returns "" on any failure or when nothing relevant
     * is stored — memory is best-effort and must never break a turn.
     */
    String recall(String userInput, String scope) {
        String resolved = effectiveScope(scope);
        try {
            List<ResponseInputItem> items = List.of(userMessage(userInput));
            MemorySearchOptions options = new MemorySearchOptions().setMaxMemories(maxRecall);
            MemoryStoreSearchResponse result =
                    memoryStores.searchMemories(storeName, resolved, items, null, options);
            if (result == null || result.getMemories() == null || result.getMemories().isEmpty()) {
                return "";
            }
            List<String> lines = new ArrayList<>();
            int used = 0;
            for (MemorySearchItem searchItem : result.getMemories()) {
                MemoryItem item = searchItem == null ? null : searchItem.getMemoryItem();
                if (item == null || item.getContent() == null || item.getContent().isBlank()) {
                    continue;
                }
                if (isExcludedKind(item)) {
                    continue;
                }
                String line = "- " + item.getContent().trim();
                if (recallCharBudget > 0 && used + line.length() > recallCharBudget && !lines.isEmpty()) {
                    break; // honour the char budget once at least one fact is included
                }
                lines.add(line);
                used += line.length() + 1;
            }
            LOG.info("recall: returned={} injected={} scope={}",
                    result.getMemories().size(), lines.size(), resolved);
            return String.join("\n", lines);
        } catch (RuntimeException e) {
            LOG.warn("recall failed (continuing without memory): {}", e.getMessage());
            return "";
        }
    }

    private boolean isExcludedKind(MemoryItem item) {
        if (excludeKinds.isEmpty() || item.getKind() == null) {
            return false;
        }
        return excludeKinds.contains(item.getKind().toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Starts an asynchronous memory update from the just-completed exchange. The store extracts
     * durable facts server-side after {@code updateDelaySeconds} of inactivity; we deliberately do not
     * block on the poller — the write outlives the request.
     */
    void remember(String userInput, String assistantOutput, String scope) {
        String resolved = effectiveScope(scope);
        if (!worthRemembering(userInput)) {
            LOG.info("remember: skipped low-value turn for scope={}", resolved);
            return;
        }
        if (secretGuard && (containsSecret(userInput) || containsSecret(assistantOutput))) {
            LOG.info("remember: skipped turn (secret guard) for scope={}", resolved);
            return;
        }
        try {
            List<ResponseInputItem> items = new ArrayList<>();
            items.add(userMessage(cap(userInput)));
            if (assistantOutput != null && !assistantOutput.isBlank()) {
                items.add(assistantMessage(cap(assistantOutput)));
            }
            memoryStores.beginUpdateMemories(storeName, resolved, items, null, updateDelaySeconds);
            LOG.info("remember: queued memory update for scope={}", resolved);
        } catch (RuntimeException e) {
            LOG.warn("remember failed (memory not updated): {}", e.getMessage());
        }
    }

    /**
     * Write-worthiness gate: a cheap rule-based filter for the turns least likely to carry a durable
     * fact (blank, too short, or a bare pleasantry) — the first structural defence against extractor
     * bloat.
     */
    private boolean worthRemembering(String userInput) {
        if (userInput == null) {
            return false;
        }
        String trimmed = userInput.trim();
        if (trimmed.length() < Math.max(1, minInputChars)) {
            return false;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[!.?,]+$", "").trim();
        return !PLEASANTRIES.contains(normalized);
    }

    private boolean containsSecret(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Pattern p : SECRET_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private String cap(String text) {
        if (text == null) {
            return "";
        }
        return (maxItemChars > 0 && text.length() > maxItemChars)
                ? text.substring(0, maxItemChars) : text;
    }

    private static ResponseInputItem userMessage(String text) {
        return message("user", text == null ? "" : text);
    }

    private static ResponseInputItem assistantMessage(String text) {
        return message("assistant", text);
    }

    /**
     * Builds a Responses input message with an explicit {@code type:"message"}. The Foundry memory
     * data-plane parser rejects items that omit {@code type} (unlike the model Responses endpoint,
     * which tolerates the abbreviated "easy" message shape), so we must set it here.
     */
    private static ResponseInputItem message(String role, String text) {
        return ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
                .type(ResponseInputItem.Message.Type.MESSAGE)
                .role(ResponseInputItem.Message.Role.of(role))
                .addInputTextContent(text)
                .build());
    }
}
