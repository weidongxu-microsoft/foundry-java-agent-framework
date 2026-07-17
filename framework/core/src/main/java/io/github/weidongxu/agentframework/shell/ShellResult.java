package io.github.weidongxu.agentframework.shell;

/** The outcome of running a shell probe command. */
public final class ShellResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public ShellResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }
}
