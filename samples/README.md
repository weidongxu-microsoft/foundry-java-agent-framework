# Samples

Runnable, progressive examples for the **Java Agent Framework** — the Java counterpart to MAF's
`python/samples/` and `dotnet/samples/`. Structure mirrors MAF's numbered progression.

> **Status:** scaffold only — folders + READMEs are in place; sample code is being added
> incrementally (start with `01-get-started/`). See [`../plan/16-samples-parity.md`](../plan/16-samples-parity.md).

## Structure

| Folder | Description |
|--------|-------------|
| [`01-get-started/`](./01-get-started/) | Progressive tutorial: hello agent → tools → multi-turn → memory → workflow → hosting |
| [`02-agents/`](./02-agents/) | Deep-dive by concept: tools, middleware, context providers, MCP, skills, observability |
| [`03-workflows/`](./03-workflows/) | Workflow patterns: sequential, concurrent, handoff, group-chat |
| [`04-hosting/`](./04-hosting/) | Deployment: Foundry hosted agent, container |
| [`05-end-to-end/`](./05-end-to-end/) | Full applications and demos |

## Build model

Each sample is an **independent build** that consumes the framework as installed artifacts — the
same rule as the `app/`/`client/`/`admin/` workloads (samples are **not** modules of the framework
parent pom). Install the framework first:

```powershell
mvn -q install                        # framework (repo root) → ~/.m2
mvn -q -f samples\01-get-started\pom.xml compile   # once a sample has a pom
```
