package com.example.memadmin;

import com.azure.ai.agents.AgentsClient;
import com.azure.ai.agents.AgentsClientBuilder;
import com.azure.ai.agents.BetaMemoryStoresClient;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.AzureCliCredentialBuilder;

/**
 * Command-line administration tool for a Foundry hosted agent. It groups two surfaces:
 *
 * <ul>
 *   <li><b>Memory-store admin</b> ({@link MemoryCommands}) — project-wide: enumerate stores,
 *       list/inspect memories in a scope, delete a memory, wipe a scope. Backed by
 *       {@link BetaMemoryStoresClient}.</li>
 *   <li><b>Hosted-agent lifecycle</b> ({@link AgentCommands}) — update the deployment (bump image +
 *       env vars, creating a new version), enable/disable the agent, and manage runtime sessions.
 *       Backed by {@link AgentsClient}.</li>
 * </ul>
 *
 * <p>Whereas the hosted agent's {@code MemoryService} drives <em>per-turn</em> recall/remember, this
 * tool performs <em>project-wide</em> administration and a tool-free deploy path (the same operation
 * the Foundry {@code agent_update} MCP tool performs).</p>
 *
 * <p><b>Where the API lives:</b> in Java both surfaces are in {@code azure-ai-agents}
 * ({@link BetaMemoryStoresClient} and {@link AgentsClient}). In Python both are exposed by
 * {@code azure-ai-projects} — a cross-language package split that is easy to get wrong.</p>
 *
 * <h2>Auth &amp; configuration</h2>
 * <ul>
 *   <li>{@code FOUNDRY_PROJECT_ENDPOINT} (required) — e.g.
 *       {@code https://<account>.services.ai.azure.com/api/projects/<project>}</li>
 *   <li>{@code MEMORY_STORE_NAME} (optional, default {@code memstore-hostagent})</li>
 *   <li>Auth uses {@link com.azure.identity.AzureCliCredential} (the {@code az login} identity),
 *       matching the {@code client} workload; the signed-in principal must hold a data-plane role on
 *       the project.</li>
 * </ul>
 */
public final class AdminCli {

    private static final String DEFAULT_STORE = "memstore-hostagent";

    private AdminCli() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        String endpoint = Cli.env("FOUNDRY_PROJECT_ENDPOINT", null);
        if (endpoint == null || endpoint.isBlank()) {
            System.err.println("ERROR: FOUNDRY_PROJECT_ENDPOINT is required "
                    + "(e.g. https://<account>.services.ai.azure.com/api/projects/<project>).");
            System.exit(2);
        }
        String store = Cli.env("MEMORY_STORE_NAME", DEFAULT_STORE);

        TokenCredential credential = new AzureCliCredentialBuilder().build();
        String cmd = args[0];

        try {
            if (isAgentCommand(cmd)) {
                dispatchAgent(cmd, args, endpoint, credential);
            } else {
                dispatchMemory(cmd, args, store, endpoint, credential);
            }
        } catch (RuntimeException e) {
            System.err.println("FAILED (" + cmd + "): " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean isAgentCommand(String cmd) {
        return switch (cmd) {
            case "update-agent", "disable-agent", "enable-agent",
                    "sessions", "stop-session", "stop-sessions" -> true;
            default -> false;
        };
    }

    private static void dispatchAgent(String cmd, String[] args, String endpoint, TokenCredential credential) {
        AgentsClient agents = new AgentsClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildAgentsClient();
        switch (cmd) {
            case "update-agent" -> AgentCommands.update(agents, args);
            case "disable-agent" -> AgentCommands.setEnabled(agents, args, false);
            case "enable-agent" -> AgentCommands.setEnabled(agents, args, true);
            case "sessions" -> AgentCommands.listSessions(agents, args);
            case "stop-session" -> AgentCommands.stopSession(agents, args);
            case "stop-sessions" -> AgentCommands.stopSessions(agents, args);
            default -> { /* unreachable */ }
        }
    }

    private static void dispatchMemory(String cmd, String[] args, String store,
            String endpoint, TokenCredential credential) {
        BetaMemoryStoresClient client = new AgentsClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .beta()
                .buildBetaMemoryStoresClient();
        switch (cmd) {
            case "stores" -> MemoryCommands.stores(client);
            case "list" -> {
                Cli.requireArgs(args, 2, "list <scope>");
                MemoryCommands.list(client, store, args[1]);
            }
            case "list-all" -> MemoryCommands.listAll(client, store);
            case "get" -> {
                Cli.requireArgs(args, 2, "get <memoryId>");
                MemoryCommands.get(client, store, args[1]);
            }
            case "delete-memory" -> {
                Cli.requireArgs(args, 2, "delete-memory <memoryId>");
                MemoryCommands.deleteMemory(client, store, args[1]);
            }
            case "delete-scope" -> {
                Cli.requireArgs(args, 2, "delete-scope <scope> [--yes]");
                boolean yes = args.length > 2 && "--yes".equals(args[2]);
                MemoryCommands.deleteScope(client, store, args[1], yes);
            }
            default -> {
                System.err.println("Unknown command: " + cmd);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                Foundry hosted-agent admin

                Env:
                  FOUNDRY_PROJECT_ENDPOINT   required  (https://<acct>.services.ai.azure.com/api/projects/<proj>)
                  MEMORY_STORE_NAME          optional  (default: memstore-hostagent)

                Memory-store commands:
                  stores                       List memory stores in the project.
                  list <scope>                 List all memories in a scope (e.g. demo-user).
                  list-all                     Best-effort list across ALL scopes.
                  get <memoryId>               Show one memory by id.
                  delete-memory <memoryId>     Delete a single memory by id.
                  delete-scope <scope> [--yes] Delete EVERY memory in a scope (prompts unless --yes).

                Hosted-agent commands:
                  update-agent <name> [--image <img>] [--env K=V]...
                                               Create a new version of a HOSTED agent, overriding the
                                               container image and/or merging environment variables into
                                               the latest version's definition (get -> mutate -> create).
                  disable-agent <name>         Stop a HOSTED agent (stops the running container to save
                                               cost). Versions/config are preserved.
                  enable-agent <name>          Re-enable a previously disabled hosted agent.
                  sessions <name>              List the agent's runtime sessions (containers).
                  stop-session <name> <id>     Stop one runtime session by id.
                  stop-sessions <name> [--yes] Stop ALL runtime sessions (prompts unless --yes).

                Examples:
                  mvn -q compile exec:java -Dexec.args="stores"
                  mvn -q compile exec:java -Dexec.args="list demo-user"
                  mvn -q compile exec:java -Dexec.args="delete-scope demo-user --yes"
                  mvn -q compile exec:java -Dexec.args="update-agent java-hosted-agent --image acr.azurecr.io/java-hosted-agent:tag --env MCP_ENABLED=true --env SKILLS_ENABLED=true"
                  mvn -q compile exec:java -Dexec.args="disable-agent java-hosted-agent"
                """);
    }
}
