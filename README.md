# Java Agent Framework

A Java agent framework for Azure AI Foundry — the Java counterpart to
[microsoft/agent-framework](https://github.com/microsoft/agent-framework) (MAF), which today ships
only .NET and Python. It provides the building blocks for **agents, tools, memory, and the
hosted/client split**, plus a server side for running an agent as a Foundry **hosted agent**
(OpenAI Responses protocol) — for which there is no official Java adapter.

> **Status:** early, `0.1.0-SNAPSHOT`. Not yet published to Maven Central — build and install
> locally (see below). Group id `io.github.weidongxu`, base package
> `io.github.weidongxu.agentframework`. Framework core targets **Java 11**.

## ⚠️ Most important deploy gotcha: Java must trust the Foundry egress-proxy CA

**Any Java hosted agent running in a Foundry sandbox must add the platform's egress-proxy CA to the
JVM truststore at container startup — or every outbound HTTPS call fails.** This is the single
biggest difference from MAF's .NET/Python and the first thing to get right when deploying.

- **Why (Java-only):** the Foundry sandbox routes outbound HTTPS through an egress proxy that
  **terminates TLS with a platform CA not in the JVM `cacerts`**. The **JVM ships its own `cacerts`
  and ignores the OS trust store** (`/etc/ssl/certs`) — so calls to the project endpoint fail with
  `SSLHandshakeException` / **PKIX path building failed**. .NET and Python do **not** hit this: their
  TLS stacks read the OS store (where the platform installed the CA), so they trust it with no code.
  There is no MAF feature to mirror here — it is purely a JVM trust-source gap.
- **Fix:** at container start, import the CA the platform exposes via `NODE_EXTRA_CA_CERTS`
  (e.g. `/etc/ssl/certs/adc-egress-proxy-ca.crt`) into a **writable copy** of `cacerts` and launch
  with `-Djavax.net.ssl.trustStore=...`. Importing just that one cert (not all ~140 system CAs) keeps
  startup fast so the readiness probe passes. Reference implementation: **[`app/entrypoint.sh`](app/entrypoint.sh)**.
- **Roadmap:** this is currently solved in the **workload** (`app/`), not the framework — a candidate
  to pull into the framework's hosting/runtime layer (a startup truststore-augmentation) so workloads
  need zero TLS plumbing.

## Framework modules (`framework/`) — the product

| Module | Purpose |
|--------|---------|
| `core` | Core abstractions: agents, chat client/messages, tools, memory, context providers. |
| `core-impl` | Default implementations of the core abstractions. |
| `openai` | `ChatClient` over the OpenAI **Responses** protocol (sync + streaming/SSE). |
| `foundry` | Azure AI Foundry integration: memory-store provider, hosted-tool wiring, config/auth helpers. |
| `langchain4j` | LangChain4j `ChatClient` bridge (alternative chat backend). |
| `mcp` | Local **MCP** integration: tools (`McpToolSource`) and Agent Skills (`McpSkillSource`) over stdio. |
| `workflows` | Multi-step workflow orchestration. |
| `observability-opentelemetry` | OpenTelemetry GenAI spans/metrics. |
| `agentserver-responses` | Server side of the Foundry hosted-agent **Responses** HTTP contract. |
| `agentserver-spring` | Spring Boot binding for the agent server. |
| `agentserver-foundry` | Foundry hosting glue (platform headers, lifecycle) for the agent server. |

The root pom aggregates **only** the `framework/*` modules.

## Test workloads (not the deliverable)

Kept as reference workloads that exercise the framework end-to-end. They are **independent builds**
that depend on the framework as installed artifacts.

| Dir | What |
|-----|------|
| `app/` | Spring Boot hosted agent implementing Foundry `/responses` (web search + memory + tools/skills). |
| `client/` | Console client that invokes a deployed agent end-to-end and verifies its features. |
| `admin/` | Memory-store + hosted-agent lifecycle admin CLI. |

## Samples (`samples/`)

Progressive, runnable examples mirroring MAF's `python/samples/` / `dotnet/samples/` (`01-get-started`
→ `05-end-to-end`). Currently a **scaffold** (folders + READMEs mapped to framework surfaces); sample
code is being added incrementally. See [`samples/README.md`](samples/README.md).

## Build & test

Install the framework first, then build each workload independently.

```powershell
# 1. Framework reactor (the product) — build, test, publish to the local ~/.m2 repo
mvn -q install                      # or: mvn -q -DskipTests install

# 2. Workloads — independent builds that resolve the framework from the local repo
mvn -q -f app\pom.xml test          # hosted-agent (Spring Boot fat jar → Docker)
mvn -q -f client\pom.xml compile    # console client
mvn -q -f admin\pom.xml compile     # memory-store / lifecycle admin utility
```

Run the smallest targeted test rather than the full suite, e.g.:

```powershell
mvn -q -f framework\mcp\pom.xml -Dtest=McpToolSourceTest test
```

## Docs

- **`AGENTS.md`** — repository conventions and workflow guide.

## References

- Parity target: https://github.com/microsoft/agent-framework
- Foundry hosted agents: https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/hosted-agents
