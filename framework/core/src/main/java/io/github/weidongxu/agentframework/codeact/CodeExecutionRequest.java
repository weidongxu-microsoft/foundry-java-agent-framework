package io.github.weidongxu.agentframework.codeact;

import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable snapshot of the {@link CodeActProvider} state captured at the start of a single
 * {@code execute_code} invocation and passed to the {@link CodeExecutor}. Mirrors the MAF
 * {@code CodeExecutor.RunSnapshot} plus the code to run.
 */
public final class CodeExecutionRequest {
    private final String code;
    private final List<Tool> hostTools;
    private final List<FileMount> fileMounts;

    public CodeExecutionRequest(String code, List<Tool> hostTools, List<FileMount> fileMounts) {
        this.code = code;
        this.hostTools = Collections.unmodifiableList(
                new ArrayList<>(hostTools == null ? Collections.emptyList() : hostTools));
        this.fileMounts = Collections.unmodifiableList(
                new ArrayList<>(fileMounts == null ? Collections.emptyList() : fileMounts));
    }

    /** The source code to execute. */
    public String getCode() {
        return code;
    }

    /** The host tools that should be callable from within the executed code. */
    public List<Tool> getHostTools() {
        return hostTools;
    }

    /** The file mounts exposed to the executed code. */
    public List<FileMount> getFileMounts() {
        return fileMounts;
    }
}
