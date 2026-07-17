package io.github.weidongxu.agentframework.codeact;

/**
 * The outcome of a single {@link CodeExecutor} run, mirroring how MAF's {@code CodeExecutor}
 * assembles captured {@code stdout}/{@code stderr} and the top-level {@code result} value into the
 * tool output returned to the model.
 */
public final class CodeExecutionResult {
    private final String stdout;
    private final String stderr;
    private final String result;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;

    public CodeExecutionResult(String stdout, String stderr, String result) {
        this(stdout, stderr, result, false, false);
    }

    public CodeExecutionResult(
            String stdout, String stderr, String result, boolean stdoutTruncated, boolean stderrTruncated) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.result = result;
        this.stdoutTruncated = stdoutTruncated;
        this.stderrTruncated = stderrTruncated;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    /** The value of a top-level {@code result} variable, when the executor captured one. */
    public String getResult() {
        return result;
    }

    public boolean isStdoutTruncated() {
        return stdoutTruncated;
    }

    public boolean isStderrTruncated() {
        return stderrTruncated;
    }

    /**
     * Assembles the model-facing tool output from stdout, stderr, and the {@code result} value,
     * mirroring MAF's {@code CodeExecutor.BuildContents}.
     */
    public String toToolOutput() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) {
            sb.append(stdout);
            if (stdoutTruncated) {
                sb.append("\n[stdout truncated]");
            }
        }
        if (stderr != null && !stderr.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("stderr:\n").append(stderr);
            if (stderrTruncated) {
                sb.append("\n[stderr truncated]");
            }
        }
        if (result != null && !result.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("result:\n").append(result);
        }
        if (sb.length() == 0) {
            return "Code executed successfully without output.";
        }
        return sb.toString();
    }
}
