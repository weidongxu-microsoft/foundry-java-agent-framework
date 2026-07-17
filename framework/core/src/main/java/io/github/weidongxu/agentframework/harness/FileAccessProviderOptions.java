package io.github.weidongxu.agentframework.harness;

/** Options for {@link FileAccessProvider}. Mirrors MAF's {@code FileAccessProviderOptions}. */
public final class FileAccessProviderOptions {
    private String instructions;
    private boolean disableWriteTools;
    private boolean disableReadOnlyToolApproval;
    private boolean disableWriteToolApproval;

    /** Custom instructions. When {@code null}, the provider uses its built-in instructions. */
    public String getInstructions() {
        return instructions;
    }

    public FileAccessProviderOptions setInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /** When true, only the read-only tools (read, ls, grep) are exposed. */
    public boolean isDisableWriteTools() {
        return disableWriteTools;
    }

    public FileAccessProviderOptions setDisableWriteTools(boolean disableWriteTools) {
        this.disableWriteTools = disableWriteTools;
        return this;
    }

    /** When true, the read-only tools (read, ls, grep) do not require approval. */
    public boolean isDisableReadOnlyToolApproval() {
        return disableReadOnlyToolApproval;
    }

    public FileAccessProviderOptions setDisableReadOnlyToolApproval(boolean disableReadOnlyToolApproval) {
        this.disableReadOnlyToolApproval = disableReadOnlyToolApproval;
        return this;
    }

    /** When true, the store-modifying tools (write, delete, replace, replace_lines) do not require approval. */
    public boolean isDisableWriteToolApproval() {
        return disableWriteToolApproval;
    }

    public FileAccessProviderOptions setDisableWriteToolApproval(boolean disableWriteToolApproval) {
        this.disableWriteToolApproval = disableWriteToolApproval;
        return this;
    }

    public static FileAccessProviderOptions defaults() {
        return new FileAccessProviderOptions();
    }
}
