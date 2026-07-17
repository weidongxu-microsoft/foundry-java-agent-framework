package io.github.weidongxu.agentframework.tool;

/**
 * Hosted image-generation tool. The host generates images from the model's
 * prompts. Parity with Python {@code SupportsImageGenerationTool} and the .NET
 * hosted image-generation tool.
 */
public final class HostedImageGenerationTool extends HostedTool {

    private final String model;

    public HostedImageGenerationTool() {
        this(null);
    }

    /**
     * @param model an explicit image model id, or {@code null} for the host default.
     */
    public HostedImageGenerationTool(String model) {
        super("image_generation");
        this.model = model;
    }

    /** @return the configured model id, or {@code null} for the host default. */
    public String getModel() {
        return model;
    }
}
