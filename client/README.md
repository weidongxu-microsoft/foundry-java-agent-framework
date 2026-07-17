# Demo client — invoking the Foundry hosted agent from Java

A standalone Java console app that invokes the deployed **hosted agent** and verifies its two
features end‑to‑end:

1. **Web search** — asks a current‑events question and asserts the response contains a
   `web_search_call` block plus a `url_citation` annotation.
2. **Memory** — seeds a throwaway fact, then in a separate turn asks the agent to recall it
   (server‑side memory store, partitioned by the caller's Microsoft Entra identity).

This is a **client**, not the agent. It does not use `azure-ai-agents` (that is the build /
management SDK for *creating* agents, memory stores, and the backing prompt agent). Per the
[Foundry hosted‑agents docs](https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/hosted-agents?view=foundry),
a hosted agent is invoked through its **agent endpoint** with **any OpenAI‑compatible SDK**:

```
{project_endpoint}/agents/{name}/endpoint/protocols/openai/responses
```

So the only dependencies are the **OpenAI Java SDK** (the wire client) and **azure-identity**
(for the Entra bearer token) — both on Maven Central.

## Auth

Microsoft Entra ID only — there is **no API‑key mode** for the agent endpoint. `DefaultAzureCredential`
resolves your `az login` token (or a CI service principal). The calling identity must hold the
**Foundry User** role on the Foundry account/project (the least‑privilege built‑in role that grants
agent invocation).

## Two Java‑SDK‑specific gotchas (already handled in code)

The OpenAI **Java** SDK auto‑detects the `*.azure.com` host and, by default, rewrites the URL to the
legacy Azure deployment path (`{base}/openai/deployments/{model}/responses`), which the agent
endpoint rejects. Two builder settings fix it:

| Setting | Why |
| --- | --- |
| `.azureUrlPathMode(AzureUrlPathMode.UNIFIED)` | Posts straight to `{base}/responses` instead of the legacy `deployments/` path. |
| `.putQueryParam("api-version", "2025-11-15-preview")` | The Azure data plane requires an `api-version`; the plain OpenAI protocol doesn't send one. |

(The Python / JS / C# OpenAI SDKs don't need these because you use their plain `OpenAI` client and
set `base_url` directly — the host‑based Azure auto‑detection is specific to the Java SDK.)

## Run

```powershell
$env:FOUNDRY_PROJECT_ENDPOINT = "https://<account>.services.ai.azure.com/api/projects/<project>"
# optional overrides:
$env:AGENT_NAME  = "java-hosted-agent"        # default
$env:API_VERSION = "2025-11-15-preview"       # default

mvn -q compile exec:java
# run a single scenario by NAME (e.g. the backend-identity check):
mvn -q compile exec:java "-Dexec.args=backend-identity"
# names are space- or comma-separated:
mvn -q compile exec:java "-Dexec.args=smoke,web-search"
```

Selectable scenarios: `smoke`, `web-search`, `memory`, `code-interpreter`, `multi-turn`,
`streaming`, `todo`, `git-mcp`, `changelog-skill`, `backend-identity`. An empty selection runs
them all.

Exit code is `0` only if the selected tests pass.

**`backend-identity` — chat backend identity** asserts the hosted agent stamped
`metadata.chat_client` on the response. Set `EXPECTED_CHAT_CLIENT=langchain4j` (or `foundry`) to
also assert the exact backend — verifying the `CHAT_CLIENT` toggle on the app end-to-end.

## Expected output

The `smoke` scenario prints the **chat backend** the hosted agent used, read from the response
`metadata` (`chat_client`/`chat_model`) — so a `langchain4j`-backed deployment
(`CHAT_CLIENT=langchain4j` on the app) is distinguishable from the default `foundry` path:

```
---- smoke: plain chat ----
A: Why did the developer go broke? He used up all his cache.
  chat backend : langchain4j (model=gpt-5.4)
  => PASS
```

```
---- web-search ----
A: The current CEO of Microsoft is Satya Nadella ... [Satya Nadella - Wikipedia](...)
  web_search_call present : true
  url_citation present    : true
  => PASS

---- memory ----
Seed:   ... my secret project codename is "Blue-xxxxxxxx" ...
Recall: Blue-xxxxxxxx
  memory_search_call present  : true
  codename recalled in answer : true
  => PASS
```
