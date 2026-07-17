package io.github.weidongxu.agentframework.codeact;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeActProviderTest {

    private static final Agent STUB = new Agent() {
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

    private static AIContext provide(CodeActProvider provider) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB, new AgentSession(), AIContext.empty()))
                .toCompletableFuture().get();
    }

    private static Tool toolNamed(AIContext context, String name) {
        return context.getTools().stream()
                .filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void advertisesInstructionsAndExecuteCodeTool() throws Exception {
        CodeActProvider provider = new CodeActProvider(
                request -> CompletableFuture.completedFuture(new CodeExecutionResult("", "", null)));
        AIContext context = provide(provider);
        assertNotNull(context.getInstructions());
        assertTrue(context.getInstructions().contains("execute_code"));
        assertNotNull(toolNamed(context, "execute_code"));
    }

    @Test
    void executeCodeDelegatesToExecutorAndFormatsOutput() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        CodeActProvider provider = new CodeActProvider(request -> {
            received.set(request.getCode());
            return CompletableFuture.completedFuture(
                    new CodeExecutionResult("hello\n", "", "42"));
        });
        Tool executeCode = toolNamed(provide(provider), "execute_code");
        String output = executeCode.invoke(args("code", "print('hello')")).toCompletableFuture().get();
        assertEquals("print('hello')", received.get());
        assertTrue(output.contains("hello"));
        assertTrue(output.contains("result:\n42"));
    }

    @Test
    void emptyCodeReturnsError() throws Exception {
        CodeActProvider provider = new CodeActProvider(
                request -> CompletableFuture.completedFuture(new CodeExecutionResult("", "", null)));
        Tool executeCode = toolNamed(provide(provider), "execute_code");
        String output = executeCode.invoke(args("code", "   ")).toCompletableFuture().get();
        assertTrue(output.contains("must not be empty"));
    }

    @Test
    void hostToolsAndMountsAppearInDescription() throws Exception {
        Tool hostTool = new FunctionTool("db_query", "Runs a database query.",
                Collections.singletonMap("type", "object"),
                a -> CompletableFuture.completedFuture("ok"));
        CodeActProviderOptions options = CodeActProviderOptions.defaults()
                .addHostTool(hostTool)
                .addFileMount(new FileMount("/data", "/host/data", FileMountMode.READ_WRITE));
        CodeActProvider provider = new CodeActProvider(
                request -> CompletableFuture.completedFuture(new CodeExecutionResult("", "", null)), options);
        Tool executeCode = toolNamed(provide(provider), "execute_code");
        String description = executeCode.getDescription();
        assertTrue(description.contains("db_query"));
        assertTrue(description.contains("Runs a database query."));
        assertTrue(description.contains("/data"));
        assertTrue(description.contains("READ_WRITE"));
    }

    @Test
    void executorReceivesRegisteredHostTools() throws Exception {
        AtomicReference<CodeExecutionRequest> captured = new AtomicReference<>();
        CodeActProvider provider = new CodeActProvider(request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(new CodeExecutionResult("", "", null));
        });
        provider.addHostTools(new FunctionTool("t1", "d", Collections.singletonMap("type", "object"),
                a -> CompletableFuture.completedFuture("ok")));
        Tool executeCode = toolNamed(provide(provider), "execute_code");
        executeCode.invoke(args("code", "x=1")).toCompletableFuture().get();
        assertEquals(1, captured.get().getHostTools().size());
        assertEquals("t1", captured.get().getHostTools().get(0).getName());
    }

    @Test
    void languageNameIsReflected() throws Exception {
        CodeActProvider provider = new CodeActProvider(
                request -> CompletableFuture.completedFuture(new CodeExecutionResult("", "", null)),
                CodeActProviderOptions.defaults().setLanguageName("JavaScript"));
        AIContext context = provide(provider);
        assertTrue(context.getInstructions().contains("JavaScript"));
        assertTrue(toolNamed(context, "execute_code").getDescription().contains("JavaScript"));
    }

    @Test
    void resultOutputAssemblyHandlesStderr() {
        CodeExecutionResult result = new CodeExecutionResult("out", "boom", null);
        String output = result.toToolOutput();
        assertTrue(output.contains("out"));
        assertTrue(output.contains("stderr:\nboom"));
    }

    @Test
    void emptyResultReportsSuccess() {
        assertEquals("Code executed successfully without output.",
                new CodeExecutionResult("", "", null).toToolOutput());
    }
}
