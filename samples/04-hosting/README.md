# 04 — Hosting

Deployment of an agent, mirroring MAF's `04-hosting/`. Only the in-scope Java hosting paths are
targeted.

> **Status:** scaffold — no code yet. Planned examples below.

| Target | Framework surface |
|--------|-------------------|
| foundry-hosted-agent | `agentserver-responses`, `agentserver-spring`, `agentserver-foundry` (Foundry `/responses` protocol) |
| container | Docker image → ACR → Foundry hosted agent (see `app/` + `plan/15-agent-deploy-rest-api.md`) |

The `app/` workload is the working reference for both.

**N/A for now** (see `plan/11`): Azure Functions, Durable Tasks, A2A hosting.
