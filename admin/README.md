# Foundry hosted-agent admin

A tiny CLI to administer a Foundry **hosted agent** and the **memory store** that backs it —
enumerate stores, list/inspect memories in a scope, delete a single memory, or wipe an
entire scope (e.g. one user). It can also **update the hosted-agent deployment** (bump the
container image and/or environment variables, creating a new agent version) and manage the
agent's lifecycle (enable/disable, list/stop runtime sessions).

Entry point: `com.example.memadmin.AdminCli`. Commands are grouped into `MemoryCommands`
(memory store) and `AgentCommands` (hosted-agent lifecycle).

This complements the agent's per-turn `MemoryService` (recall/remember). Here we do
**project-wide** memory administration plus a tool-free deploy path.

## Where the API lives (cross-language note)

| Language | Package | Memory entry point | Agent-update entry point |
|----------|---------|--------------------|--------------------------|
| **Java** (this tool) | `azure-ai-agents` | `BetaMemoryStoresClient` | `AgentsClient.createAgentVersion` |
| Python | `azure-ai-projects` | `project_client.memory_stores` | `project_client.agents.create_version` |

The same capabilities sit in **different packages per language** — easy to get wrong.

## Configuration

| Env var | Required | Default | Notes |
|---------|----------|---------|-------|
| `FOUNDRY_PROJECT_ENDPOINT` | yes | – | `https://<account>.services.ai.azure.com/api/projects/<project>` |
| `MEMORY_STORE_NAME` | no | `memstore-hostagent` | Not used by `update-agent`. |

Auth uses `AzureCliCredential` (so `az login` / `az account set` fully control the identity,
matching the `client` workload). The signed-in principal needs a Foundry data-plane role on
the project. Make sure the right subscription is active:

```powershell
az account set --subscription <subscription-id>
$env:FOUNDRY_PROJECT_ENDPOINT = "https://<account>.services.ai.azure.com/api/projects/<project>"
```

## Commands

```text
stores                       List memory stores in the project.
list <scope>                 List all memories in a scope (e.g. demo-user).
list-all                     Best-effort list across ALL scopes (service requires a scope -> usually 400).
get <memoryId>               Show one memory by id.
delete-memory <memoryId>     Delete a single memory by id.
delete-scope <scope> [--yes] Delete EVERY memory in a scope. Prompts unless --yes.
update-agent <name> [--image <img>] [--env K=V]...
                             Create a new version of a HOSTED agent, overriding the container image
                             and/or merging env vars into the latest version's definition.
disable-agent <name>         Stop a HOSTED agent (stops the running container to save cost).
enable-agent <name>          Re-enable a previously disabled hosted agent.
sessions <name>              List the agent's runtime sessions (containers).
stop-session <name> <id>     Stop one runtime session by id.
stop-sessions <name> [--yes] Stop ALL runtime sessions. Prompts unless --yes.
```

Run with Maven:

```powershell
mvn -q compile exec:java "-Dexec.args=stores"
mvn -q compile exec:java "-Dexec.args=list demo-user"
mvn -q compile exec:java "-Dexec.args=get <memoryId>"
mvn -q compile exec:java "-Dexec.args=delete-memory <memoryId>"
mvn -q compile exec:java "-Dexec.args=delete-scope demo-user --yes"
mvn -q compile exec:java "-Dexec.args=update-agent java-hosted-agent --image <acr>.azurecr.io/java-hosted-agent:TAG --env MCP_ENABLED=true --env SKILLS_ENABLED=true"
mvn -q compile exec:java "-Dexec.args=disable-agent java-hosted-agent"
```

## update-agent (tool-free deploy)

`update-agent` replicates what the Foundry `agent_update` MCP tool does, but from Java so you
don't need the MCP tooling connected. It follows the safe **get → mutate → create-version**
pattern:

1. Fetch the agent's **latest version** definition (`getAgent(name).getVersions().getLatest()`).
2. Require it to be a **hosted** agent (else it aborts).
3. If `--image` is given, replace the container image; otherwise keep the current one.
4. Merge every `--env K=V` into the existing environment map (existing keys you don't mention
   are preserved; matching keys are overwritten).
5. Create a **new agent version** from the mutated definition and print the old→new image, the
   changed env keys, and the new version id.

Because it reuses the fetched definition, all other settings (cpu/memory, protocol versions,
telemetry, RAI) are carried forward unchanged.

- This creates a **real new version** of the live agent — run it deliberately, not as a test.
- Env values equal to the literal string `true` are what the app's feature gates require
  (`MCP_ENABLED`, `SKILLS_ENABLED`); absent/other values leave those features off.
- The new version may not auto-activate; if not, start it from the Foundry portal (or via
  `agent_container_control`).

## Notes & limitations

- **Listing requires a scope.** The service rejects a scope-less list with
  `400 "Scope is required"`, and there is **no "list scopes" API**. `list-all` is a
  best-effort attempt that will usually fail — use `list <scope>` instead.
- **Memory ids are unique store-wide**, so `get` and `delete-memory` do **not** take a
  scope — just the id (which you obtain from `list <scope>`).
- `delete-scope` is destructive and irreversible; it removes every memory under that
  scope. It prompts for confirmation unless you pass `--yes`.
- In production each end user gets a distinct, Entra-derived scope injected by the
  platform (`x-agent-user-isolation-key`). `demo-user` is only the container's env
  default used when no per-user key is present (e.g. direct calls to the raw endpoint).
