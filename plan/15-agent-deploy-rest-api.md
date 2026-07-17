# Foundry hosted-agent deploy + lifecycle via SDK / raw data-plane REST (no foundry-mcp)

Captured while deploying `java-hosted-agent` v7 (langchain4j backend) for the client `backend-identity` scenario, and
adding enable/disable + session controls to the `admin` utility.
Use this when the foundry-mcp `agent_update` / `agent_container_control` tools are **not**
available (e.g. the Copilot CLI session). Prefer the `azure-ai-agents` SDK (`AgentsClient`,
wrapped by `admin`); the raw REST routes below are the fallback.

## Constants

- **Project endpoint (`PE`):**
  `https://<account>.services.ai.azure.com/api/projects/<project>`
- **api-version:** `2025-11-15-preview`
- **Token scope (data-plane):** `https://ai.azure.com/.default`
  (`az account get-access-token --scope https://ai.azure.com/.default --query accessToken -o tsv`)
- Auth header on every call: `Authorization: Bearer <token>`.

## Calls (verified working)

| Op | Verb + URL | Notes |
|----|-----------|-------|
| Get agent (all versions) | `GET  {PE}/agents/{name}?api-version=...` | Returns `versions.latest.definition` — mirror this shape to build the write body. |
| **Create new version** | `POST {PE}/agents/{name}?api-version=...` | Body `{ "definition": { ... } }`. Auto-increments the version; `@latest` re-points to it. **`PUT` returns 405 — use `POST`.** |
| Invoke (responses) | `POST {PE}/agents/{name}/endpoint/protocols/openai/responses?api-version=...` | Body `{ "model": "...", "input": "..." }`. |
| **Disable (stop, save cost)** | `POST {PE}/agents/{name}:disable?api-version=...` | Stops the running container; agent `state` → `disabled`. Invoke then returns **403 `AgentDisabled`**. Versions/config preserved. |
| **Enable (start)** | `POST {PE}/agents/{name}:enable?api-version=...` | Re-serves with no rebuild. |
| List sessions | `GET  {PE}/agents/{name}/endpoint/sessions?api-version=...` | Runtime sessions (containers), paged (`data`/`has_more`). Note: `/endpoint/sessions`, **not** `/agents/{name}/sessions` (404). |
| Stop one session | `POST {PE}/agents/{name}/endpoint/sessions/{id}:stop?api-version=...` | |

> Prefer the **SDK over raw REST**: `azure-ai-agents` `AgentsClient` exposes `getAgent`,
> `createAgentVersion`, `enableAgent`/`disableAgent`, `listSessions`, `stopSession`. The `admin`
> workload wraps these as `update-agent` / `enable-agent` / `disable-agent` / `sessions` /
> `stop-session` / `stop-sessions`. The action-verb REST routes above were reverse-engineered from
> the SDK's `AgentsImpl$AgentsService` route templates — use them only when the SDK isn't handy.

## Write-body `definition` shape

Mirror the GET's `versions.latest.definition` (note: `container_configuration.image` +
`protocol_versions`, *not* the flat `image`/`container_protocol_versions` shown in the
foundry-mcp skill doc):

```json
{ "definition": {
  "kind": "hosted",
  "cpu": "1",
  "memory": "2Gi",
  "container_configuration": { "image": "<acr>.azurecr.io/<repo>:<tag>" },
  "environment_variables": { "MODEL": "gpt-5-4", "CHAT_CLIENT": "langchain4j", ... },
  "protocol_versions": [ { "protocol": "responses", "version": "1.0.0" } ]
} }
```

## Container lifecycle

- **No explicit start call was needed.** After `POST` the new version goes
  `status: creating` → `active` (polls to active in ~10s via the GET), and the runtime
  **auto-starts** the container for `@latest`. The responses endpoint served the new image
  on first invoke.
- The foundry-mcp `agent_container_control (start/stop)` maps to the agent-level
  **`:disable` / `:enable`** action verbs above — **not** to any
  `{PE}/agents/{name}/containers|container|containerStatus` path (all 404). `:disable` is the
  "stop to save cost" lever; `:enable` restarts with no rebuild.
- **Sessions ≠ the serving container.** `/endpoint/sessions` lists per-conversation runtime
  sessions (idle ones auto-expire, ~30-day TTL); they are not the main cost. The always-on
  serving replica is governed by the agent's enabled/disabled `state`.

## Image build (no local Docker)

`az acr build --registry <acr> --image <repo>:<TAG> --platform linux/amd64 --file app/Dockerfile .`
(context = repo root; `TAG` = timestamp `yyyyMMddHHmm`). The Dockerfile build stage must
`install` **every** framework module the app depends on — including `framework/langchain4j`
when `CHAT_CLIENT=langchain4j` is in play (see commit `e874f34`).

## langchain4j backend env (Azure OpenAI compat surface)

`CHAT_CLIENT=langchain4j`,
`LANGCHAIN4J_BASE_URL=https://<account>.openai.azure.com/openai/v1`,
`LANGCHAIN4J_API_KEY=<key>`, `LANGCHAIN4J_MODEL=gpt-5-4`. LangChain4j `OpenAiChatModel` sends
`Authorization: Bearer <key>` to `{baseUrl}/chat/completions`; the Azure `/openai/v1/` compat
surface accepts it. Minimize tool surface for a plain redeploy:
`CODE_INTERPRETER_ENABLED=false, MCP_ENABLED=false, SKILLS_ENABLED=false, TODO_TOOL_ENABLED=false`.

## Verify

`metadata.chat_client` on the response identifies the live backend (server-stamped by
`AgentResponseHandler.withResponseMetadata`). The client **`backend-identity`** scenario (`EXPECTED_CHAT_CLIENT=langchain4j`)
asserts it.
