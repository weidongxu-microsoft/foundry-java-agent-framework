package io.github.weidongxu.agentframework.codeact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * A {@link CodeExecutor} that runs code by writing it to a temporary file and invoking a configured
 * interpreter (for example {@code python}) as a child process, capturing stdout/stderr.
 *
 * <p><strong>Security:</strong> this is <em>not</em> a sandbox — it inherits the host's process,
 * filesystem, and network access. Use it only where the surrounding environment already provides
 * isolation (Foundry hosted agents, containers, dedicated VMs). For untrusted code, supply a
 * sandbox-backed {@link CodeExecutor} instead.</p>
 *
 * <p>Unlike MAF's runner script, this minimal executor does not bridge host tools into the code or
 * capture a top-level {@code result} variable; it reports stdout/stderr only.</p>
 */
public final class LocalCodeExecutor implements CodeExecutor {
    private final List<String> command;
    private final String fileExtension;
    private final Duration timeout;
    private final Path workingDirectory;
    private final Map<String, String> environment;

    public LocalCodeExecutor() {
        this(Arrays.asList("python"), ".py", Duration.ofSeconds(30), null, null);
    }

    public LocalCodeExecutor(List<String> command, String fileExtension, Duration timeout,
            Path workingDirectory, Map<String, String> environment) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must be non-empty");
        }
        this.command = new ArrayList<>(command);
        this.fileExtension = fileExtension == null ? ".txt" : fileExtension;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
    }

    @Override
    public CompletionStage<CodeExecutionResult> execute(CodeExecutionRequest request) {
        return CompletableFuture.supplyAsync(() -> runBlocking(request.getCode()));
    }

    private CodeExecutionResult runBlocking(String code) {
        Path scriptFile = null;
        try {
            scriptFile = Files.createTempFile("codeact-", fileExtension);
            Files.write(scriptFile, (code == null ? "" : code).getBytes(StandardCharsets.UTF_8));

            List<String> full = new ArrayList<>(command);
            full.add(scriptFile.toString());
            ProcessBuilder builder = new ProcessBuilder(full);
            builder.redirectErrorStream(false);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            if (environment != null) {
                builder.environment().putAll(environment);
            }

            Process process = builder.start();
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            boolean finished = process.waitFor(Math.max(1, timeout.getSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CodeExecutionResult(stdout, "Execution timed out.", null);
            }
            return new CodeExecutionResult(stdout, stderr, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CodeExecutionResult(null, "Execution failed: " + e.getMessage(), null);
        } finally {
            if (scriptFile != null) {
                try {
                    Files.deleteIfExists(scriptFile);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
