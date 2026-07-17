package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.mcp.McpToolSource;
import io.github.weidongxu.agentframework.tool.FunctionTool;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — tools from an MCP server.
 *
 * <p>The framework acts as an MCP <em>client</em>: {@link McpToolSource#stdio} spawns an external
 * MCP server and {@code listTools()} adapts its tools into framework {@link FunctionTool}s the
 * agent can call. An allow-list restricts which tools are exposed.
 *
 * <p>Requires an MCP server on the machine. This sample defaults to the git server via
 * {@code uvx mcp-server-git}; override with {@code MCP_COMMAND} / {@code MCP_ARGS} (comma-separated)
 * / {@code MCP_REPO}.
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.Mcp
 * }</pre>
 */
public final class Mcp {

    private Mcp() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String command = Support.env("MCP_COMMAND", "uvx");
        String repo = Support.env("MCP_REPO", ".");
        List<String> mcpArgs = splitCsv(Support.env(
                "MCP_ARGS", "mcp-server-git,--repository," + repo));
        List<String> allowed = List.of("git_status", "git_log");

        try (McpToolSource mcp = McpToolSource.stdio(command, mcpArgs, null, allowed)) {
            List<FunctionTool> tools = mcp.listTools();
            System.out.println("MCP tools available: "
                    + tools.stream().map(FunctionTool::getName).toList());

            ChatClient chatClient = Support.chatClient(apiKey, executor);
            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("repo-agent")
                    .instructions("You inspect a git repository using the provided read-only tools. "
                            + "Summarize what you find in one or two sentences.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .tools(tools)
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "What is the current status of the repository?";
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }

    private static List<String> splitCsv(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
