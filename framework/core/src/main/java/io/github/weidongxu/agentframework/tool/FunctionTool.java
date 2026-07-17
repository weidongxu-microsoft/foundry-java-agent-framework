package io.github.weidongxu.agentframework.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class FunctionTool implements Tool {
    private final String name;
    private final String description;
    private final Map<String, Object> parametersSchema;
    private final ContextualToolHandler handler;
    private final ApprovalMode approvalMode;

    public FunctionTool(
            String name,
            String description,
            Map<String, Object> parametersSchema,
            ToolHandler handler) {
        this(name, description, parametersSchema, handler, ApprovalMode.NEVER);
    }

    public FunctionTool(
            String name,
            String description,
            Map<String, Object> parametersSchema,
            ToolHandler handler,
            ApprovalMode approvalMode) {
        this(name, description, parametersSchema,
                adapt(Objects.requireNonNull(handler, "handler")), approvalMode);
    }

    public FunctionTool(
            String name,
            String description,
            Map<String, Object> parametersSchema,
            ContextualToolHandler handler) {
        this(name, description, parametersSchema, handler, ApprovalMode.NEVER);
    }

    public FunctionTool(
            String name,
            String description,
            Map<String, Object> parametersSchema,
            ContextualToolHandler handler,
            ApprovalMode approvalMode) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.parametersSchema = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(parametersSchema, "parametersSchema")));
        this.handler = Objects.requireNonNull(handler, "handler");
        this.approvalMode = Objects.requireNonNull(approvalMode, "approvalMode");
    }

    private static ContextualToolHandler adapt(ToolHandler handler) {
        return (arguments, context) -> handler.invoke(arguments);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return parametersSchema;
    }

    @Override
    public ApprovalMode getApprovalMode() {
        return approvalMode;
    }

    @Override
    public CompletionStage<String> invoke(Map<String, Object> arguments) {
        return invoke(arguments, ToolContext.empty());
    }

    @Override
    public CompletionStage<String> invoke(Map<String, Object> arguments, ToolContext context) {
        return handler.invoke(
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(arguments, "arguments"))),
                Objects.requireNonNull(context, "context"));
    }
}
