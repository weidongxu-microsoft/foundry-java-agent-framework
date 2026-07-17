# 01 — Get started

Progressive tutorial mirroring MAF's `01-get-started/`. Work through in order; each step adds one
concept on top of the last.

> **Status:** `hello-agent` implemented (runnable); the rest are planned.

| # | Sample | Concept | Framework surface | Status |
|---|--------|---------|-------------------|--------|
| 1 | [`HelloAgent`](src/main/java/io/github/weidongxu/agentframework/samples/getstarted/HelloAgent.java) | Create and run your first agent | `core/agent/Agent`, `impl/ChatClientAgent`, `openai/OpenAIResponsesChatClient` | ✅ |
| 2 | add-tools | Add function tools | `core/tool/FunctionTool` | ⬜ |
| 3 | multi-turn | Multi-turn conversation | `core/agent/AgentSession` | ⬜ |
| 4 | memory | Agent memory via a context provider | `AIContextProvider` (e.g. `FileMemoryProvider`) | ⬜ |
| 5 | workflow-with-agents | Call agents inside a workflow | `framework/workflows` | ⬜ |
| 6 | host-your-agent | Host the agent (Foundry Responses) | `agentserver-*` | ⬜ |

Independent build — resolves the framework from `~/.m2` (run `mvn install` at the repo root first).

## Run `HelloAgent`

Uses OpenAI (or any OpenAI-compatible endpoint) via the OpenAI Responses API:

```powershell
mvn -q install                              # framework → ~/.m2 (once)

$env:OPENAI_API_KEY = "sk-..."
# optional: $env:OPENAI_MODEL = "gpt-4o-mini"                 # default
# optional: $env:OPENAI_BASE_URL = "https://<endpoint>/v1"    # OpenAI-compatible host

mvn -q -f samples\01-get-started\pom.xml compile exec:java
# custom prompt:
mvn -q -f samples\01-get-started\pom.xml compile exec:java "-Dexec.args=Explain records in one sentence."
```

Expected output:

```
Q: Say hello, then share one fun fact about the Java programming language.
A: Hello! Fun fact: Java was originally called "Oak" before being renamed in 1995.
```

See [`../../plan/16-samples-parity.md`](../../plan/16-samples-parity.md).
