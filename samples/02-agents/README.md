# 02 — Agents (deep dive)

One focused example per framework concept, mirroring MAF's `02-agents/`. Only concepts the Java
framework already ships are targeted (see `plan/11-parity-matrix.md`).

> **Status:** ten examples implemented (runnable). All require `OPENAI_API_KEY`
> (`OPENAI_MODEL` optional, default `gpt-4o-mini`; `OPENAI_BASE_URL` optional).

| Sample | Topic | Framework surface | Status |
|--------|-------|-------------------|--------|
| [`FunctionTools`](src/main/java/io/github/weidongxu/agentframework/samples/agents/FunctionTools.java) | tools | `core/tool/FunctionTool` | ✅ |
| [`AgentAsTool`](src/main/java/io/github/weidongxu/agentframework/samples/agents/AgentAsTool.java) | agent-as-tool | `core/tool/AgentTool` | ✅ |
| [`StructuredOutput`](src/main/java/io/github/weidongxu/agentframework/samples/agents/StructuredOutput.java) | structured-output | `core/chat/ResponseFormat` | ✅ |
| [`Middleware`](src/main/java/io/github/weidongxu/agentframework/samples/agents/Middleware.java) | middleware | `core/middleware/AgentMiddleware` | ✅ |
| [`ChatClients`](src/main/java/io/github/weidongxu/agentframework/samples/agents/ChatClients.java) | chat-client backends | `openai/`, `langchain4j/` | ✅ |
| [`Compaction`](src/main/java/io/github/weidongxu/agentframework/samples/agents/Compaction.java) | compaction | `core/compaction/CompactionProvider` | ✅ |
| [`Conversations`](src/main/java/io/github/weidongxu/agentframework/samples/agents/Conversations.java) | conversations / threads | `impl/InMemoryChatHistoryProvider` | ✅ |
| [`Observability`](src/main/java/io/github/weidongxu/agentframework/samples/agents/Observability.java) | observability | `observability-opentelemetry/*` | ✅ |
| [`Mcp`](src/main/java/io/github/weidongxu/agentframework/samples/agents/Mcp.java) | mcp | `mcp/McpToolSource` | ✅ |
| [`Skills`](src/main/java/io/github/weidongxu/agentframework/samples/agents/Skills.java) | skills | `core/skill/*` | ✅ |

Run a sample by its main class:

```powershell
mvn -q -f samples\02-agents\pom.xml compile ^
    exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.AgentAsTool
```

`Mcp` needs an MCP server on the machine (defaults to `uvx mcp-server-git`); `Skills` needs a
`SKILLS_DIR` of skill folders; `ChatClients` selects the backend via `CHAT_CLIENT=openai|langchain4j`.

**N/A for now** (breadth, see `plan/11`): a2a, devui, declarative, evaluation harness, multimodal.
