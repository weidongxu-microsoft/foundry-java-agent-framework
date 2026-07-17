# 02 — Agents (deep dive)

One focused example per framework concept, mirroring MAF's `02-agents/`. Only concepts the Java
framework already ships are targeted (see `plan/11-parity-matrix.md`).

> **Status:** scaffold — no code yet. Planned examples below.

| Topic | Framework surface |
|-------|-------------------|
| tools | `core/tool/FunctionTool`, hosted tools (`HostedWebSearchTool`, `HostedCodeInterpreterTool`, …) |
| middleware | `core/middleware/*` (agent / function / chat pipelines) |
| context-providers | the 12 cross-language providers under `core/harness`, `core/compaction`, `core/shell`, `core/codeact`, `foundry` |
| mcp | `mcp/McpToolSource` (local, stdio/HTTP), `HostedMcpTool` (remote) |
| skills | `core/skill/*` (progressive disclosure), `mcp/McpSkillSource` |
| observability | `observability-opentelemetry/*` |
| chat-client | `openai/`, `foundry/`, `langchain4j/` backends |
| structured-output | `core/chat/ResponseFormat` |
| agent-as-tool | `core/tool/AgentTool` |

**N/A for now** (breadth, see `plan/11`): a2a, devui, declarative, evaluation harness, multimodal.
