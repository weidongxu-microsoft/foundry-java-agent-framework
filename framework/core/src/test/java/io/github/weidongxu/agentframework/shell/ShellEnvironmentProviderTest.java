package io.github.weidongxu.agentframework.shell;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellEnvironmentProviderTest {

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

    private static AIContext provide(ShellEnvironmentProvider provider) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB_AGENT, new AgentSession(), AIContext.empty()))
                .toCompletableFuture().get();
    }

    /** A fake executor that answers the version/CWD probe and reports git installed, docker missing. */
    private static ShellExecutor fakePosix(AtomicInteger calls) {
        return (command, timeout) -> {
            calls.incrementAndGet();
            if (command.contains("VERSION=")) {
                return new ShellResult(0, "VERSION=5.1.16\nCWD=/home/app", "");
            }
            if (command.startsWith("git ")) {
                return new ShellResult(0, "git version 2.40.1", "");
            }
            if (command.startsWith("docker ")) {
                return new ShellResult(127, "", "not found");
            }
            return new ShellResult(0, "", "");
        };
    }

    @Test
    void injectsPosixInstructionsWithCwdAndTools() throws Exception {
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider(
                fakePosix(new AtomicInteger()),
                ShellEnvironmentProviderOptions.defaults()
                        .setOverrideFamily(ShellFamily.POSIX)
                        .setProbeTools(java.util.Arrays.asList("git", "docker")));
        String instructions = provide(provider).getInstructions();
        assertTrue(instructions.contains("POSIX shell"));
        assertTrue(instructions.contains("5.1.16"));
        assertTrue(instructions.contains("/home/app"));
        assertTrue(instructions.contains("git (git version 2.40.1)"));
        assertTrue(instructions.contains("Not installed: docker"));
    }

    @Test
    void injectsPowerShellIdioms() throws Exception {
        ShellExecutor exec = (command, timeout) ->
                command.contains("VERSION=")
                        ? new ShellResult(0, "VERSION=7.4.0\nCWD=C:\\work", "")
                        : new ShellResult(127, "", "");
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider(exec,
                ShellEnvironmentProviderOptions.defaults()
                        .setOverrideFamily(ShellFamily.POWERSHELL)
                        .setProbeTools(java.util.Collections.emptyList()));
        String instructions = provide(provider).getInstructions();
        assertTrue(instructions.contains("PowerShell"));
        assertTrue(instructions.contains("$env:NAME"));
        assertTrue(instructions.contains("C:\\work"));
    }

    @Test
    void probesOnceAndCachesSnapshot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider(
                fakePosix(calls),
                ShellEnvironmentProviderOptions.defaults()
                        .setOverrideFamily(ShellFamily.POSIX)
                        .setProbeTools(java.util.Collections.singletonList("git")));
        provide(provider);
        int afterFirst = calls.get();
        provide(provider);
        assertEquals(afterFirst, calls.get());
    }

    @Test
    void probeFailureDegradesGracefully() throws Exception {
        ShellExecutor failing = (command, timeout) -> {
            throw new java.io.IOException("boom");
        };
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider(failing,
                ShellEnvironmentProviderOptions.defaults()
                        .setOverrideFamily(ShellFamily.POSIX)
                        .setProbeTools(java.util.Collections.singletonList("git")));
        String instructions = provide(provider).getInstructions();
        assertTrue(instructions.contains("POSIX shell"));
        assertTrue(instructions.contains("Not installed: git"));
    }

    @Test
    void addsNoTools() throws Exception {
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider(
                fakePosix(new AtomicInteger()),
                ShellEnvironmentProviderOptions.defaults().setOverrideFamily(ShellFamily.POSIX));
        AIContext context = provide(provider);
        assertTrue(context.getTools().isEmpty());
    }

    @Test
    void refreshReprobes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider(
                fakePosix(calls),
                ShellEnvironmentProviderOptions.defaults()
                        .setOverrideFamily(ShellFamily.POSIX)
                        .setProbeTools(java.util.Collections.singletonList("git")));
        provide(provider);
        int afterFirst = calls.get();
        provider.refresh();
        assertTrue(calls.get() > afterFirst);
        assertEquals(Duration.ofSeconds(5),
                ShellEnvironmentProviderOptions.defaults().getProbeTimeout());
    }
}
