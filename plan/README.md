# Design notes (`plan/`)

Design notes, decisions, and captured learnings for the **Java Agent Framework**. Add a
`plan/NN-topic.md` (zero-padded, next sequential number) for each new note and register it in the
index below. Keep entries concise (see `AGENTS.md` → *Writing docs*).

> Earlier investigation/design docs (the pre-framework "how to build a Java hosted agent" arc and
> the app→framework migration) were removed once the framework shipped; their history is preserved
> in git.

## Index

| Doc | Contents |
|-----|----------|
| [09-framework-lessons.md](09-framework-lessons.md) | Framework-realm operational gotchas: model capacity, `AzureCliCredential`/`AZURE_TENANT_ID` trap, deployment-name dashes, workload layout. |
| [11-parity-matrix.md](11-parity-matrix.md) | **Living feature-catalog parity ledger** (MAF .NET/Python → Java): per-feature status + in-scope gaps backlog. Update on every "add/adjust feature X" request. |
| [12-conversation-persistence.md](12-conversation-persistence.md) | Hosted `/responses` conversation-history persistence: why in-memory fails on cold-starts, what MAF does, and the `$HOME/.checkpoints` option. |
| [13-agentserver-modules.md](13-agentserver-modules.md) | AgentServer 3-module split (`agentserver-responses` / `agentserver-spring` / `agentserver-foundry`): layering rationale + Core/host backlog. |
| [14-durable-approvals-and-foundry-config.md](14-durable-approvals-and-foundry-config.md) | Durable `FileToolApprovalStore` (survives restart) + `FoundryConfig` auth/config convenience layer. |
| [15-agent-deploy-rest-api.md](15-agent-deploy-rest-api.md) | Deploy & manage a Foundry hosted agent via the `azure-ai-agents` SDK (wrapped by `admin`) or raw data-plane REST: create version, enable/disable, sessions. |
| [16-samples-parity.md](16-samples-parity.md) | Samples parity with MAF (`python/samples`, `dotnet/samples`): maps MAF's `01`–`05` progression to a Java `samples/` tree (independent builds). Scaffolded; code TBD. |

> **Maintaining this index:** add a row for every new `plan/NN-topic.md`. See `AGENTS.md`
> → *Design docs* for the naming + parity-ledger conventions.
