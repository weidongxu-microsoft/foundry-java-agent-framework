# 13 — AgentServer module split (Core / Responses / Foundry)

**Goal.** Mirror MAF's **AgentServer** package family in Java, and give the hosted-agent host the
container-protocol substance it's currently missing. Decided in-session: adopt a 3-module split and
start with the Core/host layer.

## MAF layering (confirmed against real source)

MAF ships the container-host SDK as a **mirror-image family in both languages** (open source, in the
Azure SDK repos — *not* the agent-framework repo):

| Layer | .NET (`azure-sdk-for-net/sdk/agentserver`) | Python (`azure-sdk-for-python/sdk/agentserver`) |
|-------|--------------------------------------------|--------------------------------------------------|
| Host foundation (web-stack-coupled) | `Azure.AI.AgentServer.Core` | `azure-ai-agentserver-core` |
| `/responses` protocol | `Azure.AI.AgentServer.Responses` | `azure-ai-agentserver-responses` |
| `/invocations` protocol | `Azure.AI.AgentServer.Invocations` | `azure-ai-agentserver-invocations` |
| MAF agent bridge | `Microsoft.Agents.AI.Foundry.Hosting` (`AddFoundryResponses`) | `agent_framework_foundry_hosting` (`ResponsesHostServer`) |

**`AgentServer.Core` file inventory** (the "host substance"):
- **Host:** `AgentHost` / `AgentHostApp` / `AgentHostBuilder` / `AgentHostOptions`, `ServerVersionRegistry`
- **Middleware (`Internal/`):** `RequestContextMiddleware`, `RequestIdMiddleware`, `ServerVersionMiddleware`, `InboundRequestLoggingMiddleware`, `RequestIdBaggagePropagator`, `W3CBaggagePropagator`
- **Platform identity (`Platform/`):** `PlatformHeaders`, `FoundryAgentRequestContext`, `PlatformContext`, `FoundryEnvironment`, `FoundryCallIdHandler` (outbound call-id forwarding)
- **Hosting (`Hosting/`):** `HealthEndpointExtensions` (`/readiness`), `SseKeepAliveSession` (SSE keep-alive)
- **Telemetry (`Telemetry/`):** OTel wiring + Foundry enrichment/baggage processors

**`AgentServer.Responses` inventory (portable protocol):** `CreateResponseRequest`, `ResponseHandler`
(the SPI you implement), `ResponseContext` (`GetHistory`/`GetInputItems`), `ResponsesProvider`,
`ResponseEventStream`, `TextResponse`, output-item builders.

## Naming: why `-spring`

MAF's **Core *is* the web-stack host** (Kestrel + ASP.NET middleware for .NET, ASGI/Hypercorn for
Python). Each language has **one** idiomatic host, so they don't name it. **Java has many** (Spring
MVC, WebFlux, Quarkus, Micronaut, servlet), so the binding **must** be named. Therefore the Java
equivalent of `AgentServer.Core` is **`agentserver-spring`** — the Spring realization of the host
layer. The protocol layer (`AgentServer.Responses`) is web-stack-agnostic and stays framework-free.

> Our current `framework/hosting-spring` fuses all three MAF layers (Core + Responses + Foundry.Hosting)
> into one Spring module, and is missing most of Core's substance.

## Target Java modules

| .NET package | Java module | Contents | Spring dep? |
|---|---|---|---|
| `Azure.AI.AgentServer.Responses` | **`agentserver-responses`** | protocol DTOs, `ResponseHandler` SPI, `ResponseContext`, `ConversationStore` + `InMemoryConversationStore` | No |
| `Azure.AI.AgentServer.Core` | **`agentserver-spring`** (rename of `hosting-spring`) | health, request-id/version/logging filters, `RequestContext` + `PlatformHeaders` + call-id forwarder, graceful drain, SSE keep-alive; wires a `ResponseHandler` into Spring MVC | **Yes** |
| `Microsoft.Agents.AI.Foundry.Hosting` | **`agentserver-foundry`** | `AgentResponseHandler` (our `Agent` → `ResponseHandler`) + `FileSystemConversationStore` | No |

Dependency direction: `agentserver-foundry` → `agentserver-responses` ← `agentserver-spring`.
Workloads (`app/`) depend on `agentserver-spring` + `agentserver-foundry`.

## What moves out of today's `OpenAIResponsesController`
- Protocol parsing + Option-B host-owned history + streaming logic → `agentserver-responses`
  (`ResponseHandler` / `ResponseContext`), invoked by a thin Spring controller in `agentserver-spring`.
- `HealthController` → `agentserver-spring` (Core role).
- `ConversationStore` / `InMemoryConversationStore` → `agentserver-responses`.
- The agent-adapter + `FileSystemConversationStore` (`$HOME/.checkpoints`) → `agentserver-foundry`.

## Core/host substance — first focus (the parity gaps)
1. **`RequestContext` + `PlatformHeaders`** — per-request identity for container protocol `2.0.0`:
   `x-agent-user-id` (partition state per user), `x-agent-foundry-call-id` (opaque caller id).
   `FoundryCallIdHandler` equivalent: forward **only** the call-id on outbound Foundry/MCP calls;
   never echo `x-agent-user-id`. Unlocks multi-user sessions + toolbox auth-on-behalf-of-user.
2. **Servlet filters** — `x-request-id` correlation (+ W3C baggage), `x-platform-server` version
   header, inbound request logging.
3. **Graceful shutdown drain** — on SIGTERM, drain in-flight requests (MAF default 30 s) before exit.
4. **SSE keep-alive** — `SseKeepAliveSession` equivalent; likely fixes the streaming (Test 6)
   cold-start flake.
5. **`FoundryEnvironment`** — typed access to `FOUNDRY_PROJECT_ENDPOINT`, `FOUNDRY_AGENT_NAME`,
   `FOUNDRY_AGENT_VERSION`, `FOUNDRY_AGENT_SESSION_ID`, `PORT` (default 8088), etc.

## Migration steps
1. Create `agentserver-responses` (move protocol + stores; define `ResponseHandler`/`ResponseContext`).
2. Rename `hosting-spring` → `agentserver-spring`; add Core substance (filters, RequestContext,
   drain, SSE keep-alive, health); thin controller delegates to `ResponseHandler`.
3. Create `agentserver-foundry` (`Agent`→`ResponseHandler` adapter + `FileSystemConversationStore`).
4. Add modules to root parent pom; rewire `app/` poms + `AgentConfiguration`.
5. `mvn install` framework; run hosting tests; build `app/`; verify no regressions (Tests 0/4/6/8/9).
6. Update `plan/11-parity-matrix.md` + this doc.

## Status — implemented

The 3-module split is done and building (framework `mvn install` green; workloads rewired).

| Module | Key classes |
|---|---|
| `agentserver-responses` | `ResponseHandler` (SPI), `ResponseRequest`/`ResponseContext`/`ResponseSink`, `PlatformContext`, `PlatformHeaders`, `FoundryEnvironment`, `ConversationStore` + `InMemoryConversationStore` |
| `agentserver-foundry` | `AgentResponseHandler` (the old `OpenAIResponsesController` agent-logic, verbatim: Option-B threading, buffered/streaming, raw/normalized SSE) |
| `agentserver-spring` | `ResponsesEndpoint` (thin controller), `HttpServletResponseSink` (SSE headers + keep-alive), `HealthController`, `PlatformRequestFilter` (request-id/server-version/logging + in-flight tracking), `InFlightRequestTracker`, `GracefulShutdown` |

- `framework/hosting-spring` deleted; its 15 logic tests moved to `AgentResponseHandlerTest` (foundry), identity/header tests to `ResponsesEndpointTest` (spring) + `PlatformContextTest` (responses).
- `app/AgentConfiguration` now wires `AgentResponseHandler` + `ResponsesEndpoint` + `HealthController` + the platform filter / tracker / graceful-shutdown beans. `app/pom.xml` + `app/Dockerfile` depend on the two new modules.
- **Implemented:** `FileSystemConversationStore` (`agentserver-foundry`) — durable JSON files under
  `$HOME/.checkpoints/conversations` (atomic temp+move; hand-written codec for all 5 content types).
  Opt-in: **in-memory remains the default** because the open-source AgentServer SDK (.NET
  `InMemoryResponsesProvider`, Python `InMemoryResponseProvider`) defaults conversation storage to
  in-memory and directs durability to a distributed backend (Redis/SQL) — there is no FileSystem
  default. (MAF's `$HOME/.checkpoints` FileSystem default is for the *workflow* `AgentSessionStore`,
  a different abstraction.) Outbound call-id forwarding (`FoundryCallIdHandler` equivalent) still TODO.

## Open questions
- Keep the raw `openai`-shaped DTOs we have, or align names closer to `CreateResponseRequest`/
  `TextResponse`? (Lean: keep our DTOs, add a `ResponseHandler` SPI around them.)
- Fold `agentserver-foundry` into the existing `foundry` module, or keep separate? (Lean: separate —
  it's the hosting bridge, distinct from client-side Foundry.)
- Async/streaming SPI shape: `ResponseContext` callback vs a returned event stream — design from
  `.NET ResponseHandler.cs` / `ResponseEventStream.cs` before finalizing.
