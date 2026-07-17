# 04 — Hosting

Deployment of an agent, mirroring MAF's `04-hosting/`. Only the in-scope Java hosting paths are
targeted.

> **Status:** minimal Spring Boot host implemented (runnable). The `app/` workload is the full
> Foundry-integrated reference (memory, hosted tools, MCP, container packaging).

| Target | Sample | Framework surface | Status |
|--------|--------|-------------------|--------|
| responses-host | [`HostingApplication`](src/main/java/io/github/weidongxu/agentframework/samples/hosting/HostingApplication.java) + [`HostingConfiguration`](src/main/java/io/github/weidongxu/agentframework/samples/hosting/HostingConfiguration.java) | `agentserver-spring/ResponsesEndpoint`, `agentserver-foundry/AgentResponseHandler` | ✅ |
| container | Docker image → ACR → Foundry hosted agent | see `app/` + `plan/15-agent-deploy-rest-api.md` | ➡️ |

The sample serves `POST /responses` (OpenAI Responses protocol) and `GET /health`, backed by an
OpenAI agent. Run it:

```powershell
$env:OPENAI_API_KEY = "sk-..."
mvn -q -f samples\04-hosting\pom.xml spring-boot:run

# then, in another shell:
curl -X POST http://localhost:8080/responses -H "Content-Type: application/json" `
  -d '{\"input\":\"Say hello in one sentence.\"}'
```

The `app/` workload is the working reference for the full Foundry-hosted, containerized path.

**N/A for now** (see `plan/11`): Azure Functions, Durable Tasks, A2A hosting.
