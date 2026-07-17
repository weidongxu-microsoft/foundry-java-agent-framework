package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileMemoryProviderTest {

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

    private FileMemoryProvider provider() {
        return new FileMemoryProvider(FileMemoryProviderOptions.defaults().setBaseDirectory(tempDir));
    }

    private static AIContext provide(FileMemoryProvider provider, AgentSession session) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB_AGENT, session, AIContext.empty()))
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
    void advertisesInstructionsAndSevenTools() throws Exception {
        AIContext context = provide(provider(), new AgentSession());
        assertNotNull(context.getInstructions());
        for (String name : Arrays.asList(
                "file_memory_write", "file_memory_read", "file_memory_delete", "file_memory_ls",
                "file_memory_grep", "file_memory_replace", "file_memory_replace_lines")) {
            assertNotNull(toolNamed(context, name), "missing tool " + name);
        }
    }

    @Test
    void noIndexMessageWhenEmpty() throws Exception {
        AIContext context = provide(provider(), new AgentSession());
        assertTrue(context.getMessages().isEmpty());
    }

    @Test
    void writeThenReadRoundTrips() throws Exception {
        FileMemoryProvider provider = provider();
        AgentSession session = new AgentSession();
        AIContext context = provide(provider, session);
        invoke(toolNamed(context, "file_memory_write"),
                args("path", "note.txt", "content", "hello world", "description", "a greeting"));
        String read = invoke(toolNamed(context, "file_memory_read"), args("path", "note.txt"));
        assertEquals("hello world", read);
    }

    @Test
    void indexInjectedAfterWriteAndListsDescription() throws Exception {
        FileMemoryProvider provider = provider();
        AgentSession session = new AgentSession();
        invoke(toolNamed(provide(provider, session), "file_memory_write"),
                args("path", "note.txt", "content", "hi", "description", "a greeting"));
        AIContext next = provide(provider, session);
        assertEquals(1, next.getMessages().size());
        String index = next.getMessages().get(0).getText();
        assertTrue(index.contains("note.txt"));
        assertTrue(index.contains("a greeting"));
    }

    @Test
    void grepFindsMatchingLines() throws Exception {
        FileMemoryProvider provider = provider();
        AgentSession session = new AgentSession();
        AIContext context = provide(provider, session);
        invoke(toolNamed(context, "file_memory_write"),
                args("path", "a.txt", "content", "alpha\nbeta\ngamma"));
        String result = invoke(toolNamed(context, "file_memory_grep"), args("pattern", "^b"));
        assertTrue(result.contains("a.txt:2: beta"));
        assertFalse(result.contains("alpha"));
    }

    @Test
    void replaceLinesEditsRange() throws Exception {
        FileMemoryProvider provider = provider();
        AgentSession session = new AgentSession();
        AIContext context = provide(provider, session);
        invoke(toolNamed(context, "file_memory_write"),
                args("path", "a.txt", "content", "l1\nl2\nl3"));
        invoke(toolNamed(context, "file_memory_replace_lines"),
                args("path", "a.txt", "start_line", 2, "end_line", 2, "content", "X"));
        assertEquals("l1\nX\nl3", invoke(toolNamed(context, "file_memory_read"), args("path", "a.txt")));
    }

    @Test
    void deleteRemovesFileAndIndexEntry() throws Exception {
        FileMemoryProvider provider = provider();
        AgentSession session = new AgentSession();
        AIContext context = provide(provider, session);
        invoke(toolNamed(context, "file_memory_write"), args("path", "a.txt", "content", "x"));
        invoke(toolNamed(context, "file_memory_delete"), args("path", "a.txt"));
        assertTrue(provide(provider, session).getMessages().isEmpty());
    }

    @Test
    void rejectsNestedPaths() throws Exception {
        AIContext context = provide(provider(), new AgentSession());
        String result = invoke(toolNamed(context, "file_memory_write"),
                args("path", "../evil.txt", "content", "x"));
        assertTrue(result.contains("\"ok\":false"));
    }

    @Test
    void sessionsAreIsolated() throws Exception {
        FileMemoryProvider provider = provider();
        AgentSession first = new AgentSession();
        AgentSession second = new AgentSession();
        invoke(toolNamed(provide(provider, first), "file_memory_write"),
                args("path", "a.txt", "content", "x"));
        String ls = invoke(toolNamed(provide(provider, second), "file_memory_ls"), args());
        assertTrue(ls.contains("No memories"));
    }
}
