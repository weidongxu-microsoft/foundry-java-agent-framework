package io.github.weidongxu.agentframework.codeact;

import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Options controlling a {@link CodeActProvider}, mirroring the configurable surface of MAF's
 * {@code LocalCodeActProviderOptions} (instructions, host tools, file mounts) in a Java-idiomatic
 * form. The language name is used only to phrase the generated instructions and tool description.
 */
public final class CodeActProviderOptions {
    private String instructions;
    private String languageName = "Python";
    private final List<Tool> hostTools = new ArrayList<>();
    private final List<FileMount> fileMounts = new ArrayList<>();

    public static CodeActProviderOptions defaults() {
        return new CodeActProviderOptions();
    }

    /** Custom context instructions. When {@code null} (default), built-in instructions are used. */
    public String getInstructions() {
        return instructions;
    }

    public CodeActProviderOptions setInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /** The language name used when phrasing instructions and the tool description. Default {@code Python}. */
    public String getLanguageName() {
        return languageName;
    }

    public CodeActProviderOptions setLanguageName(String languageName) {
        this.languageName = languageName == null ? "Python" : languageName;
        return this;
    }

    /** Host tools callable from within executed code; also listed in the tool description. */
    public List<Tool> getHostTools() {
        return hostTools;
    }

    public CodeActProviderOptions addHostTool(Tool tool) {
        if (tool != null) {
            hostTools.add(tool);
        }
        return this;
    }

    /** File mounts exposed to executed code; also listed in the tool description. */
    public List<FileMount> getFileMounts() {
        return fileMounts;
    }

    public CodeActProviderOptions addFileMount(FileMount mount) {
        if (mount != null) {
            fileMounts.add(mount);
        }
        return this;
    }
}
