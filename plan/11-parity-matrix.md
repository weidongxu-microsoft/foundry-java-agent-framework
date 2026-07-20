# 11 — Parity coverage matrix (MAF .NET/Python → Java)

**Purpose.** A living, feature-by-feature ledger of `microsoft/agent-framework` (MAF) capabilities
vs. this Java port. `plan/07` mapped the *runtime/abstraction layer* well but never enumerated the
*feature catalog*, so features layered on `AIContextProvider` (Agent Skills, local MCP) were missed
until discovered ad-hoc. This doc is the second axis: **feature-catalog parity**.

**How to maintain.**
- Every "add feature X" request must first add/adjust a row here.
- Status: ✅ done · ⚠️ partial · ❌ missing (in-scope gap) · N/A (out-of-scope breadth).
- Inventory sourced from the cloned reference repo `C:\github_lab\agent-framework` (.NET `dotnet/src`,
  Python `python/packages`), not memory. A feature counts as an **in-scope parity target** only if it
  exists in **both** .NET and Python core (cross-language) and isn't a provider/infra backend.

Legend columns: **.NET** / **Py** = present in reference; **Java** = our status.

## Agents & sessions

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| Agent base (run/stream/session lifecycle) | ✅ | ✅ | ✅ | `core/agent/Agent.java` | |
| AgentSession + state bag | ✅ | ✅ | ✅ | `core/agent/AgentSession.java` | |
| Session serialization (versioned JSON) | ✅ | ✅ | ✅ | `AgentSessionCodec`, `AgentSessionStateCodec` | |
| Response types (buffered + streaming) | ✅ | ✅ | ✅ | `AgentResponse(Update)` | |
| Run options | ✅ | ✅ | ✅ | `AgentRunOptions` | |
| **Agent-as-tool** (`AsAIFunction`) | ✅ | ✅ | ✅ | `core/tool/AgentTool`, `AgentToolOptions` | Expose an `Agent` as a `Tool` (single `query` → response text). |

## Chat client & pipeline

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| `ChatClient` provider boundary | ✅ | ✅ | ✅ | `core/chat/ChatClient.java` | |
| `ChatClientAgent` auto-composes function-invoking pipeline | ✅ | ✅ | ✅ | `core-impl/impl/ChatClientAgent.java` | |
| Streaming (`Flow.Publisher`) | ✅ | ✅ | ✅ | `ChatResponseUpdate` | JDK-native, no Reactor. |
| Structured output (`ResponseFormat` json / json-schema) | ✅ | ✅ | ✅ | `core/chat/ResponseFormat.java` | |
| Content types (text / func call / result / approvals) | ✅ | ✅ | ⚠️ | `core/chat/*Content.java` | Text/FunctionCall/FunctionResult/ToolApproval done. **Missing:** binary/image content (`DataContent`/`UriContent`) — no multimodal in/out (see below + `plan/19`). |
| Multimodal content (image/data in + out) | ✅ | ✅ | ❌ | — | Input flattened to text (`AgentResponseHandler.contentText`), model client sends text only (`OpenAIResponsesChatClient.mapMessage`), tools return `String`. OpenAI SDK vision-in (`ResponseInputImage`) available. Blocks the RAW-photo workload (`plan/19`). |

## Tools

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| Function tools (+ `ToolContext`/session scope) | ✅ | ✅ | ✅ | `core/tool/FunctionTool.java`, `ToolContext.java` | |
| Hosted web search | ✅ | ✅ | ✅ | `HostedWebSearchTool` | |
| Hosted code interpreter | ✅ | ✅ | ✅ | `HostedCodeInterpreterTool` | |
| Hosted file search | ✅ | ✅ | ✅ | `HostedFileSearchTool` | |
| Hosted image generation | ✅ | ✅ | ✅ | `HostedImageGenerationTool` | |
| Hosted (remote) MCP tool | ✅ | ✅ | ✅ | `HostedMcpTool` | Host-side MCP. |
| **Local MCP tools** (framework as client; stdio/HTTP) | ✅ | ✅ | ⚠️ | `mcp/McpToolSource.java` | Part A done. **Missing:** websocket transport, progressive `load_tool`/`unload_tool`, MCP prompts/sampling/long-running tasks, per-tool MCP approval. |
| Tool approvals (store / batch / claim / lease) | ✅ | ✅ | ✅ | `core/tool/ToolApproval*`, `InMemoryToolApprovalStore`, `FileToolApprovalStore` (durable, survives restart) | |
| Agent-as-tool (`AsAIFunction`) | ✅ | ✅ | ✅ | `core/tool/AgentTool`, `AgentToolOptions` | Wrap an `Agent` as a `Tool` for multi-agent composition. |

## Context / history / memory

`AIContextProvider` is the ambient "context middleware" seam (`provide()` inject-before,
`store()` persist-after). MAF ships **12 production providers** cross-language (.NET `dotnet/src`
+ Python `python/packages`); Java has **2**. Full enumeration (parity is the target — the framework
is the product, not any single workload archetype):

| Provider (MAF .NET / Py name) | Role | Contributes tools? | .NET | Py | Java | Java location / notes |
|---|---|---|---|---|---|---|
| `AIContextProvider` (primitive) | additive per-run seam | — | ✅ | ✅ | ✅ | `core/agent/AIContextProvider.java` |
| `FoundryMemoryProvider` | semantic memory (recall + async server extract) | no | ✅ | ✅ | ✅ | `foundry/FoundryMemoryProvider.java` |
| `AgentSkillsProvider` / `SkillsProvider` | Agent Skills (index + `load_skill`/`read_skill_resource`) | yes | ✅ | ✅ | ✅ | `core/skill/*`; `run_skill_script` deferred |
| `FileMemoryProvider` | provider-agnostic file-backed memory | no | ✅ | ✅ | ✅ | `core/harness/FileMemoryProvider.java` (7 file tools, per-session folder) |
| `CompactionProvider` | rolling history compaction/summarization (context-window mgmt) | no | ✅ | ✅ | ✅ | `core/compaction/*` (Truncation + SlidingWindow; summarization deferred) |
| `TodoProvider` | ambient todo list state + todo tools | yes | ✅ | ✅ | ✅ | `core/harness/TodoProvider.java` (5 todo tools + list injection) |
| `MessageAIContextProvider` / `MessageInjectingProvider` | inject fixed/static messages each turn | no | ✅ | ✅ | ✅ | `core/harness/MessageAIContextProvider.java` + `StaticMessageAIContextProvider` |
| `AgentModeProvider` | switchable agent "modes" (persona/policy) | no | ✅ | ✅ | ✅ | `core/harness/AgentModeProvider.java` (mode_set/mode_get + notification) |
| `ShellEnvironmentProvider` | shell/OS environment context | maybe | ✅ | ✅ | ✅ | `core/shell/*` (pluggable `ShellExecutor`; instructions only) |
| `FileAccessProvider` | file read/write context + tools | yes | ✅ | ✅ | ✅ | `core/harness/FileAccessProvider` (pluggable `AgentFileStore`; approval-gated; subdir + glob) |
| `BackgroundAgentsProvider` | spawn/track background agents | yes | ✅ | ✅ | ✅ | `core/harness/BackgroundAgentsProvider` (6 tools; per-session sub-agents; non-blocking wait) |
| `LocalCodeActProvider` / `HyperlightCodeActProvider` (Py also `MontyCodeActProvider`) | code-act (execute generated code) | yes | ✅ | ✅ | ✅ | `core/codeact/*` (pluggable `CodeExecutor`; `LocalCodeExecutor` — not a sandbox) |

**Python-only / infra-backend context providers** (out of *strict* cross-language scope per matrix
rules, but logged): `AzureAISearchContextProvider` (RAG over Azure AI Search), `Mem0ContextProvider`
+ `RedisContextProvider` (memory backends), `ContentUnderstandingContextProvider`,
`UserMemoryProvider`, `HistoryProvider`, `InstructionContextProvider`, `ToolContextProvider` /
`ToolInjectingProvider`, `NestedChatProvider`. (Excludes test doubles: `Spy`/`Mock`/`Tracking`/
`Capturing`/`Test`/`Ordered`/`OptionsObserver`.)

### Other context/history rows

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| Chat history provider | ✅ | ✅ | ✅ | `InMemoryChatHistoryProvider`, `FileChatHistoryProvider` | In-memory plus opt-in file-backed store (`$HOME/.checkpoints/chat-history`, keyed by session id); both reuse the shared `core/chat/ChatMessageJsonCodec`. |
| Skills over MCP (`McpSkillSource`) | ✅ | ✅ | ✅ | `mcp/McpSkillSource.java` | Reads `skill://index.json`, adapts `skill-md` entries into lazy `AgentSkill`s; `plan/10` Part B. |

## Middleware

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| Agent / function / chat middleware pipelines | ✅ | ✅ | ✅ | `core/middleware/*` | Typed, onion-ordered. |
| Progressive tool registry (add/remove tools per iteration) | ✅ | ✅ | ✅ | `core/middleware/ProgressiveToolRegistry.java` | |
| Message injection into run loop | ✅ | ✅ | ✅ | `core-impl/impl/MessageInjectingChatClient.java` | |

## Workflows

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| Sequential / concurrent / handoff / group-chat | ✅ | ✅ | ✅ | `workflows/workflow/*` | |
| Declarative (YAML) workflows | ✅ | ✅ | ❌ | — | Out-of-scope for now (`Workflows.Declarative`). |
| Checkpoints / durable execution | ✅ | ✅ | ❌ | — | Out-of-scope (`DurableTask`). |

## Hosting & observability

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| OpenAI Responses hosting | ✅ | ✅ | ✅ | `agentserver-foundry/AgentResponseHandler.java` (SPI in `agentserver-responses`, Spring binding in `agentserver-spring`) | |
| Responses lifecycle routes (GET `/{id}`, `/{id}/cancel`, DELETE, `/{id}/input_items`) | ✅ (SDK auto-registers) | ✅ (SDK auto-registers) | ✅ | `agentserver-responses/ResponseLifecycleService.java`, `ResponseStore.java`, `agentserver-spring/ResponseLifecycleEndpoint.java` | `ResponseStore` SPI persists full response envelopes + input items; `AgentResponseHandler` populates it on each turn; Spring endpoint serves the 4 routes. |
| Health/liveness probes | ✅ | ✅ | ✅ | `agentserver-spring/HealthController.java` | |
| Platform request context (`x-agent-user-id` / `x-agent-foundry-call-id`) | ✅ | ✅ | ✅ | `agentserver-responses/{PlatformContext,PlatformHeaders}.java`; outbound forward: core `chat/{PlatformCallContext,PlatformRequestHeaders}.java`, `foundry/{FoundryCallContext,FoundryCallIdPolicy}.java`, `openai/OpenAIResponsesChatClient`, `mcp/McpClients` | Protocol 1.0.0/2.0.0. `x-agent-foundry-call-id` is **forwarded** on all outbound paths — Foundry Storage (pipeline policy), the model call (OpenAI Responses additional header), and MCP/toolbox calls (streamable-HTTP request customizer bound via `PlatformCallContext` around tool invocation). `x-agent-user-id` is **never** forwarded. |
| Foundry environment accessors | ✅ | ✅ | ✅ | `agentserver-responses/FoundryEnvironment.java` | |
| Request-id / server-version / logging filter | ✅ | ✅ | ✅ | `agentserver-spring/PlatformRequestFilter.java` | |
| SSE keep-alive | ✅ | ✅ | ✅ | `agentserver-spring/HttpServletResponseSink.java` | `SSE_KEEPALIVE_INTERVAL`, disabled by default. |
| Graceful shutdown drain | ✅ | ✅ | ✅ | `agentserver-spring/GracefulShutdown.java` | |
| Foundry hosting toolbox proxy (over MCP) | ✅ | ✅ | ⚠️ | `app/` implements Responses | No framework toolbox-proxy module, but the framework MCP client (`mcp/McpClients`) now forwards `x-agent-foundry-call-id` on outbound toolbox/MCP calls. |
| Conversation store (chat history) | ✅ (in-mem default) | ✅ (in-mem default) | ✅ (in-mem default) | `agentserver-responses/InMemoryConversationStore.java`, `agentserver-foundry/FileSystemConversationStore.java` | In-memory is the SDK default in all 3; Java adds an opt-in `$HOME/.checkpoints` file store. |
| OpenTelemetry (agent/chat/function spans) | ✅ | ✅ | ✅ | `observability-opentelemetry/*` | |

## Providers

| Provider | Java | Notes |
|---|---|---|
| OpenAI | ✅ `openai/` | |
| Foundry (Azure AI) | ✅ `foundry/` | `FoundryClientFactory` + `FoundryConfig` (env→config, credential selection: default / azure-cli / managed-identity). |
| LangChain4j (Java-specific adapter) | ✅ `langchain4j/` | No MAF equivalent; Java ecosystem bridge. App opts in via `CHAT_CLIENT=langchain4j` (OpenAI-compatible model); the hosted agent stamps `metadata.chat_client`/`chat_model` on every response so the `client` e2e distinguishes the backend. |
| Anthropic / Gemini / Bedrock / Ollama / Mistral / CopilotStudio | N/A | Breadth; add on demand. |

## Samples

MAF ships a progressive, runnable **samples** suite per language (`python/samples/`,
`dotnet/samples/`) under an identical `01`–`05` numbering. See `plan/16-samples-parity.md`.

| Feature | .NET | Py | Java | Java location | Notes |
|---|---|---|---|---|---|
| Samples suite (`01-get-started` … `05-end-to-end`) | ✅ | ✅ | ✅ | `samples/` | All five categories implemented (21 runnable main classes) mapped to framework surfaces; each category is one independent build. `02-agents` covers tools, agent-as-tool, structured-output, middleware, chat-client backends, compaction, conversations, observability, MCP, skills. Model-backed samples are compile-verified (not run). Migration guides (AutoGen/SK) N/A — no Java predecessor. |

## Out-of-scope breadth (tracked, not targeted)

- **Protocols / surfaces:** A2A, AG-UI, CopilotStudio, DevUI, Declarative agents. N/A for now.
- **Infra backends:** Cosmos/Redis/Valkey session stores, Mem0, DurableTask. N/A (backends, not core shape).

## In-scope gaps (the actionable backlog)

The framework — not any workload — is the product, so **all cross-language context providers are
parity targets**. Priority order:

1. ~~**Agent Skills** — cross-language core.~~ ✅ **Done** (`core/skill/*`, `run_skill_script` deferred). `plan/10` Part B.
2. **Context providers — all 12 of 12 done** ✅ (see the Context/history/memory enumeration).
   - ~~**P1 `FileMemoryProvider`**~~ ✅ **Done** (`core/harness/FileMemoryProvider.java`).
   - ~~**P1 `CompactionProvider`**~~ ✅ **Done** (`core/compaction/*`; Truncation + SlidingWindow, summarization deferred).
   - ~~**P2 `TodoProvider`**~~ ✅ **Done** (`core/harness/TodoProvider.java`).
   - ~~**P2 `MessageAIContextProvider`, `AgentModeProvider`, `ShellEnvironmentProvider`**~~ ✅ **Done** (`core/harness/*`, `core/shell/*`).
   - ~~**P3 `FileAccessProvider`, `BackgroundAgentsProvider`, `CodeActProvider`**~~ ✅ **Done** (`core/harness/*`, `core/codeact/*`; `CodeActProvider` uses a pluggable `CodeExecutor` — sandboxing is the executor's responsibility).
3. ~~**Skills over MCP** (`McpSkillSource`)~~ ✅ **Done** (`mcp/McpSkillSource.java`; SEP-2640 `skill://index.json` discovery, `skill-md` entries → lazy `AgentSkill`, `archive`/`mcp-resource-template` skipped).
4. **Local MCP enhancements** ⚠️ — progressive load/unload, websocket transport, MCP prompts/sampling/tasks.
5. ~~**Agent-as-tool** (`AsAIFunction`)~~ ✅ **Done** (`core/tool/AgentTool` + `AgentToolOptions`; wraps an `Agent` as a `Tool` — single `query` param → response text; name/description default to the agent, sanitized).
6. ~~**Provider-agnostic / file-backed chat history**~~ ✅ **Done** (`core-impl/FileChatHistoryProvider`; shared `core/chat/ChatMessageJsonCodec`).
7. **Multimodal content (image/data in + out)** ❌ — `DataContent`/`UriContent` in core; accept `input_image`/`input_file` in `AgentResponseHandler`; map `input_image` in `OpenAIResponsesChatClient`; image/URL output. Motivating workload: RAW-photo tool (`plan/19`).

Next: all 12 cross-language context providers, durable tool approvals
(`FileToolApprovalStore`), and the Foundry provider auth/config convenience layer
(`FoundryConfig`) have landed. Workflow foundation, progressive tools, and the LangChain4j
adapter are already implemented — the larger framework backlog is now closed.
