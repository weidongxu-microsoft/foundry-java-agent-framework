package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.tool.ApprovalMode;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An {@link AIContextProvider} that exposes file tools over a shared, persistent folder tree via a
 * pluggable {@link AgentFileStore}, mirroring MAF's {@code FileAccessProvider}. Unlike
 * {@link FileMemoryProvider} (session-scoped scratch memory), this operates on a shared store whose
 * contents persist across sessions and agents — suitable for input data and output artifacts.
 *
 * <p>Tools: {@code file_access_write/read/delete/ls/grep/replace/replace_lines}. Subdirectories are
 * supported. All tools <strong>require approval by default</strong> ({@link ApprovalMode#ALWAYS_REQUIRE});
 * approval can be disabled per group via the options. When write tools are disabled, only the
 * read-only tools (read, ls, grep) are exposed.</p>
 */
public final class FileAccessProvider extends AIContextProvider {
    private static final String DEFAULT_INSTRUCTIONS =
            "## File Access\n"
                    + "You have access to a shared file storage area via the file_access_* tools for reading, "
                    + "writing, and managing files. These files persist beyond the current session and may be "
                    + "shared across sessions or agents.\n"
                    + "\n"
                    + "- Never delete or overwrite existing files unless the user has explicitly asked you to.\n"
                    + "- Files may be organized into subdirectories. Use file_access_ls to explore the tree "
                    + "level by level, or file_access_grep to search file contents recursively.\n"
                    + "- To make small edits, prefer file_access_replace (substring) or "
                    + "file_access_replace_lines (whole-line) over rewriting the whole file.";

    private final AgentFileStore fileStore;
    private final String instructions;
    private final boolean disableWriteTools;
    private final ApprovalMode readOnlyApproval;
    private final ApprovalMode writeApproval;
    private final Object writeLock = new Object();
    private volatile List<Tool> tools;

    public FileAccessProvider(AgentFileStore fileStore) {
        this(fileStore, FileAccessProviderOptions.defaults());
    }

    public FileAccessProvider(AgentFileStore fileStore, FileAccessProviderOptions options) {
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        Objects.requireNonNull(options, "options");
        this.instructions = options.getInstructions() != null
                ? options.getInstructions() : DEFAULT_INSTRUCTIONS;
        this.disableWriteTools = options.isDisableWriteTools();
        this.readOnlyApproval = options.isDisableReadOnlyToolApproval()
                ? ApprovalMode.NEVER : ApprovalMode.ALWAYS_REQUIRE;
        this.writeApproval = options.isDisableWriteToolApproval()
                ? ApprovalMode.NEVER : ApprovalMode.ALWAYS_REQUIRE;
    }

    @Override
    public List<String> getStateKeys() {
        return Collections.emptyList();
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        return CompletableFuture.completedFuture(
                AIContext.builder().instructions(instructions).tools(tools()).build());
    }

    private List<Tool> tools() {
        List<Tool> local = tools;
        if (local == null) {
            synchronized (this) {
                if (tools == null) {
                    tools = Collections.unmodifiableList(createTools());
                }
                local = tools;
            }
        }
        return local;
    }

    private List<Tool> createTools() {
        List<Tool> result = new ArrayList<>();
        result.add(readTool());
        result.add(lsTool());
        result.add(grepTool());
        if (!disableWriteTools) {
            result.add(writeTool());
            result.add(deleteTool());
            result.add(replaceTool());
            result.add(replaceLinesTool());
        }
        return result;
    }

    // ----- tools --------------------------------------------------------------------------------

    private Tool readTool() {
        return new FunctionTool(
                "file_access_read",
                "Read the content of a file by name. Returns the content or a not-found message.",
                objectSchema(mapOf("file_name", stringProperty("The relative path of the file to read.")),
                        Collections.singletonList("file_name")),
                args -> CompletableFuture.completedFuture(read(args)),
                readOnlyApproval);
    }

    private Tool lsTool() {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "directory", stringProperty("The relative directory to list. Omit for the root."),
                        "glob_pattern", stringProperty("An optional glob (e.g. \"*.md\") to filter entry names.")),
                Collections.emptyList());
        return new FunctionTool(
                "file_access_ls",
                "List the direct child files and subdirectories of a directory (subdirectories first).",
                schema,
                args -> CompletableFuture.completedFuture(ls(args)),
                readOnlyApproval);
    }

    private Tool grepTool() {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "regex_pattern", stringProperty("A case-insensitive regular expression to match file contents."),
                        "glob_pattern", stringProperty("An optional glob (e.g. \"**/*.md\") to filter which files are searched."),
                        "directory", stringProperty("An optional base directory to restrict the search.")),
                Collections.singletonList("regex_pattern"));
        return new FunctionTool(
                "file_access_grep",
                "Recursively search file contents using a case-insensitive regular expression.",
                schema,
                args -> CompletableFuture.completedFuture(grep(args)),
                readOnlyApproval);
    }

    private Tool writeTool() {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "file_name", stringProperty("The relative path of the file to write."),
                        "content", stringProperty("The content to write."),
                        "overwrite", boolProperty("Whether to overwrite an existing file. Defaults to false.")),
                java.util.Arrays.asList("file_name", "content"));
        return new FunctionTool(
                "file_access_write",
                "Write a file with the given name and content. Does not overwrite an existing file unless overwrite is true.",
                schema,
                args -> CompletableFuture.completedFuture(write(args)),
                writeApproval);
    }

    private Tool deleteTool() {
        return new FunctionTool(
                "file_access_delete",
                "Delete a file by name.",
                objectSchema(mapOf("file_name", stringProperty("The relative path of the file to delete.")),
                        Collections.singletonList("file_name")),
                args -> CompletableFuture.completedFuture(delete(args)),
                writeApproval);
    }

    private Tool replaceTool() {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "file_name", stringProperty("The relative path of the file to modify."),
                        "old_string", stringProperty("The substring to find and replace."),
                        "new_string", stringProperty("The replacement text."),
                        "replace_all", boolProperty("Replace every occurrence. When false, fails unless exactly one match exists.")),
                java.util.Arrays.asList("file_name", "old_string", "new_string")); 
        return new FunctionTool(
                "file_access_replace",
                "Replace occurrences of old_string with new_string in a file. Returns the number replaced.",
                schema,
                args -> CompletableFuture.completedFuture(replace(args)),
                writeApproval);
    }

    private Tool replaceLinesTool() {
        Map<String, Object> editSchema = objectSchema(
                mapOf(
                        "line_number", intProperty("The 1-based line number to replace."),
                        "new_line", stringProperty("The replacement text for the line. Empty deletes the line.")),
                java.util.Arrays.asList("line_number", "new_line"));
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "file_name", stringProperty("The relative path of the file to modify."),
                        "edits", arrayProperty(editSchema, "The 1-based line edits to apply.")),
                java.util.Arrays.asList("file_name", "edits"));
        return new FunctionTool(
                "file_access_replace_lines",
                "Replace whole lines in a file. Each edit has a 1-based line_number and literal new_line "
                        + "(empty deletes the line). Fails on out-of-range or duplicate line numbers.",
                schema,
                args -> CompletableFuture.completedFuture(replaceLines(args)),
                writeApproval);
    }

    // ----- operations ---------------------------------------------------------------------------

    private String read(Map<String, Object> args) {
        String name = str(args.get("file_name"));
        try {
            String content = fileStore.read(name);
            return content != null ? content : "File '" + name + "' not found.";
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String write(Map<String, Object> args) {
        String name = str(args.get("file_name"));
        String content = str(args.get("content"));
        boolean overwrite = bool(args.get("overwrite"));
        synchronized (writeLock) {
            try {
                if (!overwrite && fileStore.fileExists(name)) {
                    return "File '" + name + "' already exists. To replace it, write again with overwrite set to true.";
                }
                fileStore.write(name, content == null ? "" : content);
                return "File '" + name + "' written.";
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    private String delete(Map<String, Object> args) {
        String name = str(args.get("file_name"));
        synchronized (writeLock) {
            try {
                return fileStore.delete(name)
                        ? "File '" + name + "' deleted." : "File '" + name + "' not found.";
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    private String ls(Map<String, Object> args) {
        String directory = strOrEmpty(args.get("directory"));
        String glob = str(args.get("glob_pattern"));
        Pattern matcher = glob == null || glob.trim().isEmpty() ? null : globToRegex(glob);
        try {
            List<FileStoreEntry> entries = fileStore.listChildren(directory);
            List<String> dirs = new ArrayList<>();
            List<String> files = new ArrayList<>();
            for (FileStoreEntry entry : entries) {
                if (matcher != null && !matcher.matcher(entry.getName()).matches()) {
                    continue;
                }
                (entry.isDirectory() ? dirs : files).add(entry.getName());
            }
            if (dirs.isEmpty() && files.isEmpty()) {
                return "(empty)";
            }
            StringBuilder sb = new StringBuilder();
            for (String d : dirs) {
                sb.append("[dir]  ").append(d).append('\n');
            }
            for (String f : files) {
                sb.append("[file] ").append(f).append('\n');
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String grep(Map<String, Object> args) {
        String patternText = str(args.get("regex_pattern"));
        if (patternText == null || patternText.isEmpty()) {
            return "Error: invalid or missing 'regex_pattern'";
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regular expression: " + e.getMessage();
        }
        String glob = str(args.get("glob_pattern"));
        Pattern globMatcher = glob == null || glob.trim().isEmpty() ? null : globToRegex(glob);
        String directory = strOrEmpty(args.get("directory"));
        StringBuilder sb = new StringBuilder();
        try {
            for (String path : fileStore.listFilesRecursively(directory)) {
                if (globMatcher != null && !globMatcher.matcher(path).matches()) {
                    continue;
                }
                String content = fileStore.read(path);
                if (content == null) {
                    continue;
                }
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        sb.append(path).append(':').append(i + 1).append(": ").append(lines[i]).append('\n');
                    }
                }
            }
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        return sb.length() == 0 ? "No matches." : sb.toString().trim();
    }

    private String replace(Map<String, Object> args) {
        String name = str(args.get("file_name"));
        String oldStr = str(args.get("old_string"));
        String newStr = str(args.get("new_string"));
        boolean replaceAll = bool(args.get("replace_all"));
        if (oldStr == null || oldStr.isEmpty()) {
            return "Error: invalid or missing 'old_string'";
        }
        if (newStr == null) {
            newStr = "";
        }
        synchronized (writeLock) {
            try {
                String content = fileStore.read(name);
                if (content == null) {
                    return "File '" + name + "' not found.";
                }
                int count = countOccurrences(content, oldStr);
                if (count == 0) {
                    return "'old_string' not found in '" + name + "'.";
                }
                if (count > 1 && !replaceAll) {
                    return "'old_string' occurs " + count + " times in '" + name
                            + "'. Set replace_all to true to replace all.";
                }
                String updated = replaceAll ? content.replace(oldStr, newStr)
                        : replaceFirst(content, oldStr, newStr);
                fileStore.write(name, updated);
                return "Replaced " + count + " occurrence(s) in '" + name + "'.";
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String replaceLines(Map<String, Object> args) {
        String name = str(args.get("file_name"));
        Object rawEdits = args.get("edits");
        if (!(rawEdits instanceof List)) {
            return "Error: invalid or missing 'edits'";
        }
        Map<Integer, String> edits = new LinkedHashMap<>();
        for (Object element : (List<Object>) rawEdits) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<String, Object> edit = (Map<String, Object>) element;
            Integer lineNumber = toInt(edit.get("line_number"));
            if (lineNumber == null) {
                return "Error: each edit requires a 'line_number'";
            }
            if (edits.containsKey(lineNumber)) {
                return "Error: duplicate line number " + lineNumber;
            }
            edits.put(lineNumber, strOrEmpty(edit.get("new_line")));
        }
        synchronized (writeLock) {
            try {
                String content = fileStore.read(name);
                if (content == null) {
                    return "File '" + name + "' not found.";
                }
                List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\n", -1)));
                for (Integer lineNumber : edits.keySet()) {
                    if (lineNumber < 1 || lineNumber > lines.size()) {
                        return "Error: line number " + lineNumber + " is out of range (1-" + lines.size() + ").";
                    }
                }
                List<String> result = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    int lineNumber = i + 1;
                    if (edits.containsKey(lineNumber)) {
                        String replacement = edits.get(lineNumber);
                        if (!replacement.isEmpty()) {
                            result.add(replacement);
                        }
                    } else {
                        result.add(lines.get(i));
                    }
                }
                fileStore.write(name, String.join("\n", result));
                return "Replaced " + edits.size() + " line(s) in '" + name + "'.";
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    // ----- helpers ------------------------------------------------------------------------------

    /** Converts a glob (supporting {@code *}, {@code **}, {@code ?}) to a full-match regex. */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            // "**/" matches zero or more leading path segments.
                            sb.append("(?:.*/)?");
                            i++;
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '.': case '(': case ')': case '+': case '|': case '^':
                case '$': case '@': case '%': case '{': case '}': case '[': case ']':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return Pattern.compile(sb.toString());
    }

    private static int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }

    private static String replaceFirst(String content, String target, String replacement) {
        int index = content.indexOf(target);
        if (index < 0) {
            return content;
        }
        return content.substring(0, index) + replacement + content.substring(index + target.length());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String strOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && "true".equalsIgnoreCase(value.toString());
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

    private static Map<String, Object> arrayProperty(Map<String, Object> itemSchema, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "array");
        property.put("items", itemSchema);
        property.put("description", description);
        return property;
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

    private static Map<String, Object> boolProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "boolean");
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
