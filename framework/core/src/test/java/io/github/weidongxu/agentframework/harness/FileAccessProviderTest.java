package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.ApprovalMode;
import io.github.weidongxu.agentframework.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAccessProviderTest {

    @TempDir
    Path tempDir;

    private static final Agent STUB_AGENT = new Agent() {
        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }
    };

    private FileAccessProvider provider(FileAccessProviderOptions options) {
        return new FileAccessProvider(new LocalFileStore(tempDir), options);
    }

    private static AIContext provide(FileAccessProvider provider) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB_AGENT, new AgentSession(), AIContext.empty()))
                .toCompletableFuture().get();
    }

    private static Tool toolNamed(AIContext context, String name) {
        return context.getTools().stream()
                .filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static String invoke(Tool tool, Map<String, Object> args) throws Exception {
        return tool.invoke(args).toCompletableFuture().get();
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void advertisesInstructionsAndSevenToolsRequiringApproval() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        assertNotNull(context.getInstructions());
        for (String name : Arrays.asList("file_access_write", "file_access_read", "file_access_delete",
                "file_access_ls", "file_access_grep", "file_access_replace", "file_access_replace_lines")) {
            Tool tool = toolNamed(context, name);
            assertNotNull(tool, "missing tool " + name);
            assertEquals(ApprovalMode.ALWAYS_REQUIRE, tool.getApprovalMode(), name + " should require approval");
        }
    }

    @Test
    void disableWriteToolsExposesReadOnlyOnly() throws Exception {
        AIContext context = provide(provider(
                FileAccessProviderOptions.defaults().setDisableWriteTools(true)));
        assertEquals(3, context.getTools().size());
        assertNotNull(toolNamed(context, "file_access_read"));
        assertNull(toolNamed(context, "file_access_write"));
    }

    @Test
    void approvalCanBeDisabledPerGroup() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()
                .setDisableReadOnlyToolApproval(true)
                .setDisableWriteToolApproval(true)));
        assertEquals(ApprovalMode.NEVER, toolNamed(context, "file_access_read").getApprovalMode());
        assertEquals(ApprovalMode.NEVER, toolNamed(context, "file_access_write").getApprovalMode());
    }

    @Test
    void writeDoesNotOverwriteUnlessRequested() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        Tool write = toolNamed(context, "file_access_write");
        assertTrue(invoke(write, args("file_name", "a.txt", "content", "one")).contains("written"));
        assertTrue(invoke(write, args("file_name", "a.txt", "content", "two")).contains("already exists"));
        assertTrue(invoke(write, args("file_name", "a.txt", "content", "two", "overwrite", true)).contains("written"));
        assertEquals("two", invoke(toolNamed(context, "file_access_read"), args("file_name", "a.txt")));
    }

    @Test
    void readMissingFileReportsNotFound() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        assertTrue(invoke(toolNamed(context, "file_access_read"), args("file_name", "missing.txt"))
                .contains("not found"));
    }

    @Test
    void lsListsSubdirectoriesAndFilesWithGlob() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        Tool write = toolNamed(context, "file_access_write");
        invoke(write, args("file_name", "notes.md", "content", "x"));
        invoke(write, args("file_name", "data.txt", "content", "x"));
        invoke(write, args("file_name", "reports/summary.md", "content", "x"));
        String all = invoke(toolNamed(context, "file_access_ls"), args());
        assertTrue(all.contains("[dir]  reports"));
        assertTrue(all.contains("[file] notes.md"));
        String md = invoke(toolNamed(context, "file_access_ls"), args("glob_pattern", "*.md"));
        assertTrue(md.contains("notes.md"));
        assertTrue(!md.contains("data.txt"));
        String sub = invoke(toolNamed(context, "file_access_ls"), args("directory", "reports"));
        assertTrue(sub.contains("summary.md"));
    }

    @Test
    void grepSearchesRecursivelyCaseInsensitive() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        Tool write = toolNamed(context, "file_access_write");
        invoke(write, args("file_name", "a.txt", "content", "Hello World\nfoo"));
        invoke(write, args("file_name", "nested/b.txt", "content", "bar\nhello again"));
        String result = invoke(toolNamed(context, "file_access_grep"), args("regex_pattern", "hello"));
        assertTrue(result.contains("a.txt:1: Hello World"));
        assertTrue(result.contains("nested/b.txt:2: hello again"));
    }

    @Test
    void grepGlobFilterRestrictsFiles() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        Tool write = toolNamed(context, "file_access_write");
        invoke(write, args("file_name", "a.md", "content", "target"));
        invoke(write, args("file_name", "b.txt", "content", "target"));
        String result = invoke(toolNamed(context, "file_access_grep"),
                args("regex_pattern", "target", "glob_pattern", "**/*.md"));
        assertTrue(result.contains("a.md"));
        assertTrue(!result.contains("b.txt"));
    }

    @Test
    void replaceRequiresUniqueUnlessReplaceAll() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        Tool write = toolNamed(context, "file_access_write");
        Tool replace = toolNamed(context, "file_access_replace");
        invoke(write, args("file_name", "a.txt", "content", "x x x"));
        assertTrue(invoke(replace, args("file_name", "a.txt", "old_string", "x", "new_string", "y"))
                .contains("occurs 3 times"));
        assertTrue(invoke(replace, args("file_name", "a.txt", "old_string", "x", "new_string", "y",
                "replace_all", true)).contains("Replaced 3"));
        assertEquals("y y y", invoke(toolNamed(context, "file_access_read"), args("file_name", "a.txt")));
    }

    @Test
    void replaceLinesEditsAndDeletes() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        Tool write = toolNamed(context, "file_access_write");
        invoke(write, args("file_name", "a.txt", "content", "l1\nl2\nl3"));
        List<Map<String, Object>> edits = new ArrayList<>();
        edits.add(args("line_number", 2, "new_line", "L2"));
        edits.add(args("line_number", 3, "new_line", ""));
        invoke(toolNamed(context, "file_access_replace_lines"), args("file_name", "a.txt", "edits", edits));
        assertEquals("l1\nL2", invoke(toolNamed(context, "file_access_read"), args("file_name", "a.txt")));
    }

    @Test
    void deleteRemovesFile() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        invoke(toolNamed(context, "file_access_write"), args("file_name", "a.txt", "content", "x"));
        assertTrue(invoke(toolNamed(context, "file_access_delete"), args("file_name", "a.txt")).contains("deleted"));
        assertTrue(invoke(toolNamed(context, "file_access_read"), args("file_name", "a.txt")).contains("not found"));
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        AIContext context = provide(provider(FileAccessProviderOptions.defaults()));
        String result = invoke(toolNamed(context, "file_access_write"),
                args("file_name", "../evil.txt", "content", "x"));
        assertTrue(result.startsWith("Error:"));
    }
}
