package io.github.weidongxu.agentframework.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ShellExecutor} that runs probe commands in the host's default shell — PowerShell on
 * Windows, {@code /bin/sh} elsewhere. Intended only for the short, read-only version/CWD probes
 * issued by {@link ShellEnvironmentProvider}.
 */
public final class LocalShellExecutor implements ShellExecutor {
    private final ShellFamily family;

    public LocalShellExecutor() {
        this(detectFamily());
    }

    public LocalShellExecutor(ShellFamily family) {
        this.family = family;
    }

    static ShellFamily detectFamily() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? ShellFamily.POWERSHELL : ShellFamily.POSIX;
    }

    @Override
    public ShellResult run(String command, Duration timeout) throws Exception {
        ProcessBuilder builder = family == ShellFamily.POWERSHELL
                ? new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                : new ProcessBuilder("/bin/sh", "-c", command);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        String stdout = readFully(process.getInputStream());
        String stderr = readFully(process.getErrorStream());
        boolean finished = process.waitFor(
                timeout == null ? 5 : Math.max(1, timeout.getSeconds()), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("probe timed out");
        }
        return new ShellResult(process.exitValue(), stdout, stderr);
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
