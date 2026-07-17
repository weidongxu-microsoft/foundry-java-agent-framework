package com.example.memadmin;

import com.azure.ai.agents.AgentsClient;
import com.azure.ai.agents.models.AgentDefinition;
import com.azure.ai.agents.models.AgentDetails;
import com.azure.ai.agents.models.AgentSessionResource;
import com.azure.ai.agents.models.AgentState;
import com.azure.ai.agents.models.AgentVersionDetails;
import com.azure.ai.agents.models.ContainerConfiguration;
import com.azure.ai.agents.models.HostedAgentDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hosted-agent deployment and lifecycle commands. Backed by {@link AgentsClient} from
 * {@code azure-ai-agents} — {@code update-agent} mirrors the Foundry {@code agent_update} MCP tool
 * (get latest version -> mutate -> create a new version).
 */
final class AgentCommands {

    private AgentCommands() {
    }

    static void update(AgentsClient client, String[] args) {
        Cli.requireArgs(args, 2, "update-agent <name> [--image <image>] [--env K=V]...");
        String name = args[1];
        String image = null;
        Map<String, String> envOverrides = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--image" -> {
                    if (i + 1 >= args.length) {
                        Cli.fail("--image requires a value");
                    }
                    image = args[++i];
                }
                case "--env" -> {
                    if (i + 1 >= args.length) {
                        Cli.fail("--env requires a K=V value");
                    }
                    String kv = args[++i];
                    int eq = kv.indexOf('=');
                    if (eq <= 0) {
                        Cli.fail("--env expects K=V, got: " + kv);
                    }
                    envOverrides.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
                default -> Cli.fail("unknown update-agent option: " + args[i]);
            }
        }
        if (image == null && envOverrides.isEmpty()) {
            Cli.fail("nothing to change: pass --image and/or one or more --env K=V");
        }

        AgentDetails details = client.getAgent(name);
        AgentVersionDetails latest =
                details.getVersions() == null ? null : details.getVersions().getLatest();
        if (latest == null || latest.getDefinition() == null) {
            throw new IllegalStateException(
                    "agent '" + name + "' has no latest version definition to base the update on.");
        }
        AgentDefinition def = latest.getDefinition();
        if (!(def instanceof HostedAgentDefinition hosted)) {
            throw new IllegalStateException("agent '" + name + "' is not a HOSTED agent (kind="
                    + def.getKind() + "); update-agent only supports hosted agents.");
        }

        String oldImage = hosted.getContainerConfiguration() == null
                ? null : hosted.getContainerConfiguration().getImage();
        if (image != null) {
            hosted.setContainerConfiguration(new ContainerConfiguration(image));
        }

        Map<String, String> mergedEnv = new LinkedHashMap<>();
        if (hosted.getEnvironmentVariables() != null) {
            mergedEnv.putAll(hosted.getEnvironmentVariables());
        }
        mergedEnv.putAll(envOverrides);
        hosted.setEnvironmentVariables(mergedEnv);

        System.out.println("Updating hosted agent '" + name
                + "' (base version " + latest.getVersion() + "):");
        System.out.println("  image : " + (image == null
                ? oldImage + " (unchanged)" : oldImage + " -> " + image));
        System.out.println("  env   : " + (envOverrides.isEmpty()
                ? "(unchanged)" : "set " + new TreeMap<>(envOverrides)));

        AgentVersionDetails created = client.createAgentVersion(name, hosted);
        System.out.println("Created new version: " + created.getVersion()
                + " (id=" + created.getId() + ", status=" + created.getStatus() + ")");
        System.out.println("NOTE: if the new version does not auto-activate, start it via the Foundry "
                + "portal (or agent_container_control).");
    }

    /**
     * Enable or disable a hosted agent. Disabling stops the running container (the agent stops
     * serving its {@code /responses} endpoint) — the effective "stop to save cost" lever. Existing
     * versions and configuration are preserved; re-enable to bring it back with no rebuild.
     */
    static void setEnabled(AgentsClient client, String[] args, boolean enable) {
        String verb = enable ? "enable-agent" : "disable-agent";
        Cli.requireArgs(args, 2, verb + " <name>");
        String name = args[1];
        AgentState before = client.getAgent(name).getState();
        if (enable) {
            client.enableAgent(name);
        } else {
            client.disableAgent(name);
        }
        AgentState after = client.getAgent(name).getState();
        System.out.println((enable ? "Enabled" : "Disabled") + " hosted agent '" + name + "'.");
        System.out.println("  state : " + before + " -> " + after);
    }

    /** List the runtime sessions (containers) of a hosted agent. */
    static void listSessions(AgentsClient client, String[] args) {
        Cli.requireArgs(args, 2, "sessions <name>");
        String name = args[1];
        System.out.println("Sessions for agent '" + name + "':");
        int n = 0;
        for (AgentSessionResource s : client.listSessions(name)) {
            System.out.printf("- id=%s  status=%s  version=%s  lastAccessed=%s  expires=%s%n",
                    s.getAgentSessionId(),
                    s.getStatus(),
                    s.getVersionIndicator() == null ? "?" : s.getVersionIndicator(),
                    s.getLastAccessedAt(),
                    s.getExpiresAt());
            n++;
        }
        System.out.println("(" + n + " session(s))");
    }

    /** Stop a single runtime session by id. */
    static void stopSession(AgentsClient client, String[] args) {
        Cli.requireArgs(args, 3, "stop-session <name> <sessionId>");
        String name = args[1];
        String sessionId = args[2];
        client.stopSession(name, sessionId);
        System.out.println("Stopped session " + sessionId + " of agent '" + name + "'.");
    }

    /** Stop every runtime session of a hosted agent (convenience for cost cleanup). */
    static void stopSessions(AgentsClient client, String[] args) {
        Cli.requireArgs(args, 2, "stop-sessions <name> [--yes]");
        String name = args[1];
        boolean yes = args.length > 2 && "--yes".equals(args[2]);
        if (!Cli.confirm("Stop ALL sessions of agent '" + name + "'?", yes)) {
            System.out.println("Aborted.");
            return;
        }
        int n = 0;
        for (AgentSessionResource s : client.listSessions(name)) {
            client.stopSession(name, s.getAgentSessionId());
            System.out.println("  stopped " + s.getAgentSessionId());
            n++;
        }
        System.out.println("Stopped " + n + " session(s) of agent '" + name + "'.");
    }
}
