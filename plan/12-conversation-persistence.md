# 12 — Conversation-history persistence (hosted `/responses`)

**Context.** Option B makes the hosted agent *own* conversation history (MAF parity): the caller's
`conversation` / `previous_response_id` is used only as a **local store key** (never forwarded
upstream); prior turns are prepended and the model runs statelessly. See `ConversationStore` +
`InMemoryConversationStore` in `framework/hosting-spring`.

## The problem we hit

- `InMemoryConversationStore` = JVM heap.
- On Foundry we observed **`Started HostedAgentApplication` once per request** (a ~13 s cold start
  right before each request's own log line) → the container **cold-starts a fresh pod per request**
  (scale-to-zero). Heap state is wiped between turns.
- Result: the **`multi-turn`** scenario (turn 1 states a passphrase, turn 2 recalls it via `previous_response_id`)
  fails — the save from turn 1 and the load in turn 2 hit different JVMs. Single-request scenarios
  pass; only cross-request threading breaks.
- The store *logic* is correct (verified: same key saved then loaded; unit tests green). It's purely
  a **process-lifetime** issue.

## What MAF actually does (from `microsoft/agent-framework`)

MAF has **two code paths** that resolve history differently:

- **Hosted path** (`ResponsesHostServer` in Python `foundry_hosting`, `AgentFrameworkResponseHandler`
  in .NET `Foundry.Hosting`): the container calls **`context.get_history()` /
  `GetHistoryAsync()`** each request. Those turns are **not** kept in a local chat store — they are
  served by the **`azure.ai.agentserver` SDK**, backed by the **Foundry platform's**
  `ResponseProviderProtocol` (an `InMemoryResponseProvider` stands in for it in local tests).
  `store: False` (Python sample comment *"History will be managed by the hosting infrastructure"*)
  is the **model API's** persistence flag — not a container store toggle. `ResponsesHostServer`
  even **rejects** an agent with a `load_messages=True` history provider, enforcing that chat
  history comes only from the platform via `context.get_history()`.
  - ⚠️ **`azure.ai.agentserver` is a *separate* Azure-SDK dependency, not in the agent-framework
    repo — but it IS open source.** It lives in `Azure/azure-sdk-for-python` and
    `Azure/azure-sdk-for-net` under `sdk/agentserver/`, as a mirror-image package family:
    `*-core` (protocol-agnostic host: `/readiness`, SIGTERM drain, OTel, port 8088, platform
    headers) + `*-responses` (the `/responses` protocol) + `*-invocations`. MAF's
    `foundry_hosting` / `Foundry.Hosting` is a thin bridge over it. So the container↔platform wire
    protocol (incl. how history reaches the container: headers `x-agent-user-id` /
    `x-agent-foundry-call-id`, `get_request_context()`, container protocol `2.0.0`) **is fully
    readable** — we can settle the "inline vs id-only" question from source and port it to Java.
    Empirically our raw container currently receives only the id, not the prior messages.
- **Self-host / local-dev path**: the container **owns** chat history in a pluggable store keyed by
  `previous_response_id` / `conversation.id`. This is `IConversationStorage` (.NET
  `Hosting.OpenAI`, e.g. `InMemoryResponsesService`) and Python `SessionStore` + history providers.
  **Our `ConversationStore` is parity with this path.**

### Two stores, not one — `AgentSessionStore` ≠ `IConversationStorage`

A common confusion (they are **different concepts**, not a Python/.NET rename):

| MAF abstraction | Package | Stores | Backing |
|---|---|---|---|
| **`AgentSessionStore`** | .NET `Foundry.Hosting`; Python `FileCheckpointStorage` | **Workflow/session execution state** — `StateBag`, checkpoint manager, pending external-input. **NOT chat messages.** | `FileSystemAgentSessionStore` → `$HOME/.checkpoints` (registered by `AddFoundryResponses`) |
| **`IConversationStorage`** | .NET `Hosting.OpenAI`; Python `ResponseProviderProtocol` / `SessionStore` | **Conversation chat messages** (user/assistant turns), keyed by conversation/response id | in-memory (local); platform store (hosted) |

- `FileSystemAgentSessionStore` writes under **`$HOME/.checkpoints`** (`HOME` defaults to
  `/home/session`). Doc comment:
  > `$HOME` is the only container directory that is **writable and durably preserved across requests
  > for the lifetime of the session**; the root filesystem is read-only and paths outside `$HOME`
  > may be cleared between requests.
  > *"This complements Foundry storage (which owns conversation messages, agent definitions, and
  > threads) — it is not a replacement for it."*
- **Our Java `ConversationStore` maps to `IConversationStorage`** (chat turns), **not**
  `AgentSessionStore` (workflow state). If we later add workflow agents, that needs a *separate*
  session/checkpoint store — do not conflate the two.

### Durable chat-history providers (Python-only, self-hosted)

`CosmosHistoryProvider`, `RedisHistoryProvider` exist **only in Python**, only for the self-hosted
path, and are **not** wired into the hosted server. Config is **data-plane connection info, not a
resource ID**:
- Cosmos: `endpoint` + `database_name` + `container_name` + `credential` (MI `TokenCredential` or
  key); one document per message, `session_id` partition key.
- Redis: `redis_url` **or** `host`+`port`(6380)+`ssl`+`credential_provider` (Azure AD); Redis List
  per session, optional `max_messages` trim.

.NET ships no Cosmos/Redis. `InMemoryAgentSessionStore` doc: *"for multiple instances … use a
durable storage implementation such as Redis, SQL Server, or Azure Cosmos DB."*

**Takeaway:** MAF's *hosted* path relies on a **platform-managed** chat store surfaced through the
`azure.ai.agentserver` host SDK. That SDK is **open source** (`azure-sdk-for-{net,python}` under
`sdk/agentserver/`) and mirror-imaged across languages, so a **Java port is feasible** — and reading
its `*-responses` source is the way to confirm exactly how the platform delivers history to the
container. MAF *also* ships a **container-owned** path (`IConversationStorage`), which our
`ConversationStore` already mirrors. Near-term parity = container-owned history + a durable backing
(the `$HOME/.checkpoints` location); a fuller parity = a Java `AgentServer`-style host that speaks
the same container protocol.

## Options for us (no paid infra)

1. **Platform-managed history (Playground path).** MAF's hosted path gets turns from the Foundry
   platform via the `azure.ai.agentserver` host SDK (open source; `azure-sdk-for-{net,python}` under
   `sdk/agentserver/`). The Playground *may* already thread via this platform store — but it's
   surfaced through that SDK, and **empirically our raw container receives only the id, not the
   messages**. Reading the `*-responses` source will confirm whether Foundry forwards prior turns to
   a container inline or expects the container to resolve the id — if inline, threading is free. Our
   **`multi-turn` direct client does NOT replay** (only sends `previous_response_id`), so it exercises the
   container-owned path — that synthetic case is what fails.
2. **File store under `$HOME/.checkpoints` (FREE).** Mirror `FileSystemAgentSessionStore`: persist
   history JSON under `$HOME` (default `/home/session`). Relies on Foundry's guarantee that `$HOME`
   is **session-durable across requests/restarts**. ⚠️ Must **verify empirically** for our project —
   we observed per-request cold starts and need to confirm `$HOME` survives them.
3. **Cosmos / Redis (PAID).** Deferred — cost not justified right now.

## Decision

- **Skip Cosmos/Redis** for now (cost).
- Pursue **(2) the free file store**, env-gated (`CONVERSATION_STORE=file|memory`, default `memory`
  so local/tests are unaffected), writing under `$HOME/.checkpoints`. This is parity with MAF's
  **container-owned** `IConversationStorage` path + the `$HOME/.checkpoints` durable location — the
  portable parity we *can* achieve without the closed platform SDK. Redeploy and confirm `multi-turn`
  survives the cold starts. If `$HOME` persists → free multi-turn threading; if not → document the
  in-memory limitation and lean on **(1)** platform-managed history for the real Playground path.
- **Naming:** keep `ConversationStore` (= `IConversationStorage`, chat turns). Do **not** conflate
  with a future `AgentSessionStore` (workflow/checkpoint state) if workflow agents are added.

## Answer: does `.checkpoints` survive a pod restart?

- **Per MAF's design, yes — within a session.** `$HOME` (`/home/session`) is a Foundry-provided,
  **session-scoped durable mount** that is *"durably preserved across requests for the lifetime of
  the session,"* unlike the ephemeral, read-only root filesystem.
- **Caveat — which store the file default applies to.** The `$HOME/.checkpoints` *file* default is
  for the **workflow `AgentSessionStore`** (execution/checkpoint state). For **conversation chat
  history** (our `ConversationStore`'s analog — .NET `InMemoryResponsesProvider`, Python
  `InMemoryResponseProvider`) the open-source AgentServer SDK default is **in-memory**, with
  durability delegated to a distributed backend (Redis/SQL). So keeping `InMemoryConversationStore`
  as the framework default *is* the parity default; `FileSystemConversationStore` is the durable
  opt-in that borrows the same `$HOME/.checkpoints` location.

## Status — implemented

`FileSystemConversationStore` (`agentserver-foundry`) persists history as atomic JSON files under
`$HOME/.checkpoints/conversations`. Default stays `InMemoryConversationStore` (SDK parity, above).
Opt-in wiring: construct `AgentResponseHandler` with a `FileSystemConversationStore`. ⚠️ Still must
**verify empirically** that `$HOME` survives our observed per-request cold starts before relying on
it for the deployed agent.
- **Caveats:** (a) durability is scoped to the **session lifetime** (gone when the session ends /
  expires); (b) you must write **under `$HOME`** — anywhere else is read-only or cleared between
  requests; (c) it is **not shared across concurrent replicas** of the same session — fine only if
  Foundry pins a session to one pod. All three need empirical confirmation in our project before we
  rely on it.
