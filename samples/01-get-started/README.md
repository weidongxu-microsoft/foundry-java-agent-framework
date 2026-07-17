# 01 — Get started

Progressive tutorial mirroring MAF's `01-get-started/`. Work through in order; each step adds one
concept on top of the last.

> **Status:** scaffold — no code yet. Planned samples below.

| # | Sample | Concept | Framework surface |
|---|--------|---------|-------------------|
| 1 | hello-agent | Create and run your first agent | `core/agent/Agent`, a `ChatClient` |
| 2 | add-tools | Add function tools | `core/tool/FunctionTool` |
| 3 | multi-turn | Multi-turn conversation | `core/agent/AgentSession` |
| 4 | memory | Agent memory via a context provider | `AIContextProvider` (e.g. `FileMemoryProvider`) |
| 5 | workflow-with-agents | Call agents inside a workflow | `framework/workflows` |
| 6 | host-your-agent | Host the agent (Foundry Responses) | `agentserver-*` |

Each sample will be an independent build (resolves the framework from `~/.m2`). See
[`../../plan/16-samples-parity.md`](../../plan/16-samples-parity.md).
