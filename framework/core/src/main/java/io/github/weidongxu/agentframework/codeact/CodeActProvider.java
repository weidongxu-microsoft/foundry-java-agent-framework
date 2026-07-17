package io.github.weidongxu.agentframework.codeact;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link AIContextProvider} that injects a single {@code execute_code} tool (plus guidance
 * instructions) into the agent's tool surface, mirroring MAF's {@code LocalCodeActProvider} /
 * {@code HyperlightCodeActProvider} / Python {@code MontyCodeActProvider}.
 *
 * <p>The actual execution is delegated to a pluggable {@link CodeExecutor}, which is the
 * sandboxing / isolation boundary — the provider performs no isolation itself. Host tools and file
 * mounts registered on the provider are advertised in the tool description and passed to the
 * executor via the {@link CodeExecutionRequest}; whether they are truly reachable from executed
 * code depends on the executor implementation.</p>
 *
 * <p><strong>Security:</strong> executing model-generated code is dangerous. Only use a
 * {@link CodeExecutor} appropriate for your trust boundary; {@link LocalCodeExecutor} is not a
 * sandbox.</p>
 */
public final class CodeActProvider extends AIContextProvider {
    private static final String STATE_KEY = CodeActProvider.class.getName();

    private final CodeExecutor executor;
    private final String languageName;
    private final String instructions;
    private final List<Tool> hostTools = new CopyOnWriteArrayList<>();
    private final List<FileMount> fileMounts = new CopyOnWriteArrayList<>();

    public CodeActProvider(CodeExecutor executor) {
        this(executor, CodeActProviderOptions.defaults());
    }

    public CodeActProvider(CodeExecutor executor, CodeActProviderOptions options) {
        this.executor = Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(options, "options");
        this.languageName = options.getLanguageName();
        this.instructions = options.getInstructions() != null
                ? options.getInstructions() : buildContextInstructions(languageName);
        this.hostTools.addAll(options.getHostTools());
        this.fileMounts.addAll(options.getFileMounts());
    }

    @Override
    public List<String> getStateKeys() {
        return Collections.singletonList(STATE_KEY);
    }

    /** Registers additional host tools (advertised in the tool description). */
    public void addHostTools(Tool... tools) {
        for (Tool tool : tools) {
            if (tool != null) {
                hostTools.add(tool);
            }
        }
    }

    /** Registers additional file mounts (advertised in the tool description). */
    public void addFileMounts(FileMount... mounts) {
        for (FileMount mount : mounts) {
            if (mount != null) {
                fileMounts.add(mount);
            }
        }
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        List<Tool> toolsSnapshot = new ArrayList<>(hostTools);
        List<FileMount> mountsSnapshot = new ArrayList<>(fileMounts);
        String description = buildExecuteCodeDescription(languageName, toolsSnapshot, mountsSnapshot);
        Tool executeCode = executeCodeTool(description, toolsSnapshot, mountsSnapshot);
        return CompletableFuture.completedFuture(
                AIContext.builder().instructions(instructions).tool(executeCode).build());
    }

    private Tool executeCodeTool(String description, List<Tool> toolsSnapshot, List<FileMount> mountsSnapshot) {
        Map<String, Object> codeProperty = new LinkedHashMap<>();
        codeProperty.put("type", "string");
        codeProperty.put("description",
                languageName + " source code to execute in the agent environment.");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", codeProperty);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("code"));

        return new FunctionTool("execute_code", description, schema, arguments -> {
            Object raw = arguments.get("code");
            String code = raw == null ? null : raw.toString();
            if (code == null || code.trim().isEmpty()) {
                return CompletableFuture.completedFuture("Error: parameter 'code' must not be empty.");
            }
            CodeExecutionRequest request = new CodeExecutionRequest(code, toolsSnapshot, mountsSnapshot);
            return executor.execute(request).thenApply(CodeExecutionResult::toToolOutput);
        });
    }

    // ----- instruction builders (mirror MAF InstructionBuilder) ---------------------------------

    static String buildContextInstructions(String language) {
        return "You can execute " + language + " code by calling the `execute_code` tool. "
                + "Any tools listed in the tool's description are only accessible from within the executed "
                + "code — they cannot be invoked directly. "
                + "State does not persist between calls; pass any required values in the code you execute.";
    }

    static String buildExecuteCodeDescription(String language, List<Tool> tools, List<FileMount> fileMounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Executes ").append(language).append(" code in the agent environment. ");
        sb.append("Pass the full source to execute via the `code` parameter. ");
        sb.append("Returns the captured stdout/stderr and any top-level result value.");

        if (!tools.isEmpty()) {
            sb.append("\n\nThe following host tools are available inside the executed code:\n");
            for (Tool tool : tools) {
                sb.append("- `").append(tool.getName()).append('`');
                if (tool.getDescription() != null && !tool.getDescription().trim().isEmpty()) {
                    sb.append(": ").append(tool.getDescription());
                }
                sb.append('\n');
            }
        }

        if (!fileMounts.isEmpty()) {
            sb.append("\nFilesystem access:\n");
            for (FileMount mount : fileMounts) {
                sb.append("- `").append(mount.getMountPath()).append("` -> `")
                        .append(mount.getHostPath()).append("` (").append(mount.getMode()).append(")\n");
            }
        }

        return sb.toString().trim();
    }
}
