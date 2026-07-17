# 16 — Samples parity (MAF → Java)

**Goal.** Mirror MAF's runnable, progressive **samples** suite in Java. MAF has no root-level
`samples/`; each language ships its own (`python/samples/`, `dotnet/samples/`) with an identical
numbered progression. This repo had none — only `app/`/`client/`/`admin/` (integration workloads),
which are harnesses, not didactic samples.

## MAF layout (both `python/` and `dotnet/`)

| Folder | Purpose |
|--------|---------|
| `01-get-started/` | Progressive tutorial: hello agent → tools → multi-turn → memory → workflow → hosting |
| `02-agents/` | Deep-dive by concept: tools, middleware, providers, mcp, skills, observability, evaluation, multimodal, chat_client, conversations, compaction, context_providers, security |
| `03-workflows/` | Workflow patterns: sequential, concurrent, state, declarative |
| `04-hosting/` | Deployment: Azure Functions, Durable Tasks, A2A, container, foundry-hosted-agents |
| `05-end-to-end/` | Full applications, evaluation, demos |

Python also has `autogen-migration/`, `semantic-kernel-migration/`, `shared/`.

## Java layout (this repo)

Mirror the `01`–`05` numbering under a new top-level `samples/`. Each sample is an **independent
build** that resolves the framework from the local `~/.m2` (same rule as `app/`/`client/`/`admin/`
— **not** a module of the framework parent pom). Poms are added with the first code in each folder.

- `samples/01-get-started/` — hello-agent → tools → multi-turn → memory → workflow → host.
- `samples/02-agents/` — one focused example per framework concept we already ship.
- `samples/03-workflows/` — sequential / concurrent / handoff / group-chat (from `framework/workflows`).
- `samples/04-hosting/` — Foundry hosted agent + container (our in-scope hosting paths).
- `samples/05-end-to-end/` — full app; the existing `app/`+`client/` pair is the reference.

## Scope notes (parity vs. N/A)

- **In scope** (capability already exists): agents, tools, hosted tools, memory + the 12 context
  providers, MCP (local + remote), skills, middleware, compaction, workflows, OpenTelemetry,
  Foundry hosting, LangChain4j bridge.
- **N/A for now** (breadth we don't target — see `plan/11`): A2A, DevUI, declarative agents,
  Azure Functions / DurableTask hosting, migration guides (no AutoGen/SK Java predecessor).

## Status

- ✅ Plan + scaffold: `samples/` tree with `01`–`05` dirs and per-folder READMEs (no code yet).
- ⬜ Author samples per folder (start with `01-get-started`), adding an independent pom each.

See `plan/11-parity-matrix.md` → *Samples* row for the tracked status.
