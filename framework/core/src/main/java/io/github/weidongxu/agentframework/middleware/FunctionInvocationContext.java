package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FunctionInvocationContext {
    private final Tool tool;
    private final FunctionCallContent call;
    private Map<String, Object> arguments;
    private final ChatOptions options;
    private final ProgressiveToolRegistry progressiveTools;
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public FunctionInvocationContext(
            Tool tool,
            FunctionCallContent call,
            Map<String, Object> arguments,
            ChatOptions options) {
        this(tool, call, arguments, options, null);
    }

    public FunctionInvocationContext(
            Tool tool,
            FunctionCallContent call,
            Map<String, Object> arguments,
            ChatOptions options,
            ProgressiveToolRegistry progressiveTools) {
        this.tool = Objects.requireNonNull(tool, "tool");
        this.call = Objects.requireNonNull(call, "call");
        setArguments(arguments);
        this.options = Objects.requireNonNull(options, "options");
        this.progressiveTools = progressiveTools;
    }

    public Tool getTool() {
        return tool;
    }

    public FunctionCallContent getCall() {
        return call;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(arguments, "arguments")));
    }

    public ChatOptions getOptions() {
        return options;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public List<Tool> getTools() {
        return requiredProgressiveTools().getTools();
    }

    public void addTools(Tool... tools) {
        addTools(Arrays.asList(Objects.requireNonNull(tools, "tools")));
    }

    public void addTools(List<? extends Tool> tools) {
        requiredProgressiveTools().addTools(tools);
    }

    public void removeTools(String... toolNames) {
        removeTools(Arrays.asList(
                Objects.requireNonNull(toolNames, "toolNames")));
    }

    public void removeTools(List<String> toolNames) {
        requiredProgressiveTools().removeTools(toolNames);
    }

    private ProgressiveToolRegistry requiredProgressiveTools() {
        if (progressiveTools == null) {
            throw new IllegalStateException(
                    "Progressive tools are only available during a live function loop");
        }
        return progressiveTools;
    }
}
