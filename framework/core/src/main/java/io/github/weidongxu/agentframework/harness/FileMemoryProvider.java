package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * An {@link AIContextProvider} that gives an agent a private, on-disk scratch space of named text
 * memory files, mirroring the MAF {@code FileMemoryProvider}. It is <em>tool-driven only</em> — the
 * provider never auto-extracts memories; the model reads and writes files explicitly.
 *
 * <p>Each turn it (1) injects usage instructions, (2) contributes file tools, and (3) if any memory
 * exists, injects a synthetic {@code memories.md} index (≤ 50 entries) as a user message so the
 * agent is aware of what it has stored. Files live in a flat per-session folder under
 * {@code baseDirectory/<sessionId>}; nested paths are rejected. An optional companion
 * {@code <name>_description.md} holds a short description used in the index.</p>
 *
 * <p>Tools (all auto-approved): {@code file_memory_write}, {@code file_memory_read},
 * {@code file_memory_delete}, {@code file_memory_ls}, {@code file_memory_grep},
 * {@code file_memory_replace}, {@code file_memory_replace_lines}.</p>
 */
public final class FileMemoryProvider extends AIContextProvider {
    private static final String STATE_KEY = FileMemoryProvider.class.getName();
    private static final String DESCRIPTION_SUFFIX = "_description.md";
    private static final int MAX_INDEX_ENTRIES = 50;

    private static final String DEFAULT_INSTRUCTIONS =
            "## File Memory\n"
                    + "\n"
                    + "You have a private, persistent file store for remembering information across turns.\n"
                    + "Use it to save durable facts, plans, or intermediate results you may need later.\n"
                    + "\n"
                    + "Guidelines:\n"
                    + "- Use file_memory_write to save a memory to a named file; provide a short description.\n"
                    + "- Use file_memory_ls to see what you have stored and file_memory_read to recall a file.\n"
                    + "- Use file_memory_grep to search across all memories for a pattern.\n"
                    + "- Use file_memory_replace / file_memory_replace_lines to edit an existing file.\n"
                    + "- Use file_memory_delete to remove a memory that is no longer needed.\n"
                    + "- File names must be simple (no folders); nested paths are rejected.";

    private final Path baseDirectory;
    private final String instructions;

    public FileMemoryProvider() {
        this(FileMemoryProviderOptions.defaults());
    }

    public FileMemoryProvider(FileMemoryProviderOptions options) {
        Objects.requireNonNull(options, "options");
        this.baseDirectory = options.getBaseDirectory();
        this.instructions = options.getInstructions() != null
                ? options.getInstructions() : DEFAULT_INSTRUCTIONS;
    }

    @Override
    public List<String> getStateKeys() {
        return Collections.singletonList(STATE_KEY);
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        AgentSession session = context.getSession();
        Path folder = sessionFolder(session);
        AIContext.Builder builder = AIContext.builder()
                .instructions(instructions)
                .tool(writeTool(folder))
                .tool(readTool(folder))
                .tool(deleteTool(folder))
                .tool(lsTool(folder))
                .tool(grepTool(folder))
                .tool(replaceTool(folder))
                .tool(replaceLinesTool(folder));
        String index = buildIndex(folder);
        if (index != null) {
            builder.message(ChatMessage.user(index));
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    // ----- tools --------------------------------------------------------------------------------

    private Tool writeTool(Path folder) {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "path", stringProperty("The memory file name (no folders)."),
                        "content", stringProperty("The full text content to store."),
                        "description", stringProperty("An optional short description for the index.")),
                Arrays.asList("path", "content"));
        return new FunctionTool(
                "file_memory_write",
                "Create or overwrite a memory file with the given content and optional description.",
                schema,
                args -> CompletableFuture.completedFuture(write(folder, args)));
    }

    private Tool readTool(Path folder) {
        return new FunctionTool(
                "file_memory_read",
                "Read the full content of a memory file by name.",
                objectSchema(mapOf("path", stringProperty("The memory file name.")),
                        Collections.singletonList("path")),
                args -> CompletableFuture.completedFuture(read(folder, args)));
    }

    private Tool deleteTool(Path folder) {
        return new FunctionTool(
                "file_memory_delete",
                "Delete a memory file (and its description) by name.",
                objectSchema(mapOf("path", stringProperty("The memory file name.")),
                        Collections.singletonList("path")),
                args -> CompletableFuture.completedFuture(delete(folder, args)));
    }

    private Tool lsTool(Path folder) {
        return new FunctionTool(
                "file_memory_ls",
                "List all memory files with their descriptions.",
                objectSchema(new LinkedHashMap<>(), Collections.emptyList()),
                args -> CompletableFuture.completedFuture(ls(folder)));
    }

    private Tool grepTool(Path folder) {
        Map<String, Object> schema = objectSchema(
                mapOf("pattern", stringProperty("A regular expression to search for.")),
                Collections.singletonList("pattern"));
        return new FunctionTool(
                "file_memory_grep",
                "Search all memory files for lines matching a regular expression.",
                schema,
                args -> CompletableFuture.completedFuture(grep(folder, args)));
    }

    private Tool replaceTool(Path folder) {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "path", stringProperty("The memory file name."),
                        "old", stringProperty("The exact substring to replace."),
                        "new", stringProperty("The replacement text.")),
                Arrays.asList("path", "old", "new"));
        return new FunctionTool(
                "file_memory_replace",
                "Replace all occurrences of a substring within a memory file.",
                schema,
                args -> CompletableFuture.completedFuture(replace(folder, args)));
    }

    private Tool replaceLinesTool(Path folder) {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "path", stringProperty("The memory file name."),
                        "start_line", intProperty("The 1-based first line to replace (inclusive)."),
                        "end_line", intProperty("The 1-based last line to replace (inclusive)."),
                        "content", stringProperty("The replacement text for the line range.")),
                Arrays.asList("path", "start_line", "end_line", "content"));
        return new FunctionTool(
                "file_memory_replace_lines",
                "Replace an inclusive 1-based line range within a memory file with new content.",
                schema,
                args -> CompletableFuture.completedFuture(replaceLines(folder, args)));
    }

    // ----- operations ---------------------------------------------------------------------------

    private synchronized String write(Path folder, Map<String, Object> args) {
        String name = fileName(args.get("path"));
        if (name == null) {
            return error("invalid or missing 'path'");
        }
        String content = str(args.get("content"));
        if (content == null) {
            content = "";
        }
        String description = trimOrNull(str(args.get("description")));
        try {
            Files.createDirectories(folder);
            Files.write(folder.resolve(name), content.getBytes(StandardCharsets.UTF_8));
            Path descPath = folder.resolve(name + DESCRIPTION_SUFFIX);
            if (description != null) {
                Files.write(descPath, description.getBytes(StandardCharsets.UTF_8));
            } else {
                Files.deleteIfExists(descPath);
            }
            return ok("wrote " + name);
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    private synchronized String read(Path folder, Map<String, Object> args) {
        String name = fileName(args.get("path"));
        if (name == null) {
            return error("invalid or missing 'path'");
        }
        Path file = folder.resolve(name);
        if (!Files.isRegularFile(file)) {
            return error("no such memory: " + name);
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    private synchronized String delete(Path folder, Map<String, Object> args) {
        String name = fileName(args.get("path"));
        if (name == null) {
            return error("invalid or missing 'path'");
        }
        try {
            boolean removed = Files.deleteIfExists(folder.resolve(name));
            Files.deleteIfExists(folder.resolve(name + DESCRIPTION_SUFFIX));
            return removed ? ok("deleted " + name) : error("no such memory: " + name);
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    private synchronized String ls(Path folder) {
        List<String> names = listMemories(folder);
        if (names.isEmpty()) {
            return "No memories stored.";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            sb.append("- ").append(name);
            String description = readDescription(folder, name);
            if (description != null) {
                sb.append(": ").append(description);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private synchronized String grep(Path folder, Map<String, Object> args) {
        String patternText = str(args.get("pattern"));
        if (patternText == null || patternText.isEmpty()) {
            return error("invalid or missing 'pattern'");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            return error("invalid regular expression: " + e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        for (String name : listMemories(folder)) {
            try {
                String[] lines = new String(
                        Files.readAllBytes(folder.resolve(name)), StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        sb.append(name).append(':').append(i + 1).append(": ")
                                .append(lines[i]).append('\n');
                    }
                }
            } catch (IOException e) {
                // skip unreadable file
            }
        }
        return sb.length() == 0 ? "No matches." : sb.toString().trim();
    }

    private synchronized String replace(Path folder, Map<String, Object> args) {
        String name = fileName(args.get("path"));
        if (name == null) {
            return error("invalid or missing 'path'");
        }
        String oldText = str(args.get("old"));
        String newText = str(args.get("new"));
        if (oldText == null || oldText.isEmpty()) {
            return error("invalid or missing 'old'");
        }
        if (newText == null) {
            newText = "";
        }
        Path file = folder.resolve(name);
        if (!Files.isRegularFile(file)) {
            return error("no such memory: " + name);
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (!content.contains(oldText)) {
                return error("'old' text not found in " + name);
            }
            String updated = content.replace(oldText, newText);
            Files.write(file, updated.getBytes(StandardCharsets.UTF_8));
            return ok("updated " + name);
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    private synchronized String replaceLines(Path folder, Map<String, Object> args) {
        String name = fileName(args.get("path"));
        if (name == null) {
            return error("invalid or missing 'path'");
        }
        Integer start = toInt(args.get("start_line"));
        Integer end = toInt(args.get("end_line"));
        String content = str(args.get("content"));
        if (content == null) {
            content = "";
        }
        if (start == null || end == null || start < 1 || end < start) {
            return error("invalid 'start_line'/'end_line'");
        }
        Path file = folder.resolve(name);
        if (!Files.isRegularFile(file)) {
            return error("no such memory: " + name);
        }
        try {
            List<String> lines = new ArrayList<>(
                    Arrays.asList(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n", -1)));
            if (start > lines.size()) {
                return error("start_line beyond end of file");
            }
            int last = Math.min(end, lines.size());
            List<String> head = new ArrayList<>(lines.subList(0, start - 1));
            List<String> tail = new ArrayList<>(lines.subList(last, lines.size()));
            head.addAll(Arrays.asList(content.split("\n", -1)));
            head.addAll(tail);
            Files.write(file, String.join("\n", head).getBytes(StandardCharsets.UTF_8));
            return ok("updated lines " + start + "-" + end + " in " + name);
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    // ----- index / helpers ----------------------------------------------------------------------

    private String buildIndex(Path folder) {
        List<String> names = listMemories(folder);
        if (names.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("### memories.md\n");
        int count = 0;
        for (String name : names) {
            if (count++ >= MAX_INDEX_ENTRIES) {
                sb.append("- … (").append(names.size() - MAX_INDEX_ENTRIES).append(" more)\n");
                break;
            }
            sb.append("- ").append(name);
            String description = readDescription(folder, name);
            if (description != null) {
                sb.append(": ").append(description);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private List<String> listMemories(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(folder)) {
            List<String> names = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.endsWith(DESCRIPTION_SUFFIX))
                    .sorted()
                    .forEach(names::add);
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readDescription(Path folder, String name) {
        Path descPath = folder.resolve(name + DESCRIPTION_SUFFIX);
        if (!Files.isRegularFile(descPath)) {
            return null;
        }
        try {
            return trimOrNull(new String(Files.readAllBytes(descPath), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private Path sessionFolder(AgentSession session) {
        String id = session != null && session.getId() != null ? session.getId() : "default";
        return baseDirectory.resolve(id);
    }

    /** Validates a simple file name, rejecting nested or traversal paths. */
    static String fileName(Object raw) {
        String value = raw == null ? null : trimOrNull(raw.toString());
        if (value == null) {
            return null;
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")
                || value.startsWith(".") || value.endsWith(DESCRIPTION_SUFFIX)) {
            return null;
        }
        return value;
    }

    private static String ok(String message) {
        return "{\"ok\":true,\"message\":\"" + escape(message) + "\"}";
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(required));
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> intProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
