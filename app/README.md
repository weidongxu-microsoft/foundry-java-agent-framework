# app — Java hosted agent (test workload)

Spring Boot hosted agent implementing the Foundry `/responses` protocol on port **8088**.
Consumes the framework as binary artifacts (build the framework with `mvn install` first).
This is a **test/reference workload**, not the framework product.

## Capabilities (per turn)

- **Web search** — `HostedWebSearchTool` (hosted).
- **Code interpreter** — `HostedCodeInterpreterTool` (hosted, `CODE_INTERPRETER_ENABLED`).
- **Memory** — `FoundryMemoryProvider` (`AIContextProvider`), server-side store, per-scope.
- **Todo tool** — in-process `FunctionTool` with per-session scope (`TODO_TOOL_ENABLED`).
- **Local MCP tools** — optional git demo (`MCP_ENABLED`); see below.
- **Agent Skills** — optional progressive-disclosure skills (`SKILLS_ENABLED`); see below.
- **Middleware** — `MarkerMiddleware` (`AgentMiddleware`) wraps every run (`MIDDLEWARE_ENABLED`,
  default on); inert unless a turn carries the `MW_PING` sentinel, then it marks the reply so the
  `client` `middleware` scenario can assert the middleware executed in-container.

## Build & run

```powershell
mvn -q install                 # framework first (repo root) -> ~/.m2
mvn -q -f app\pom.xml test     # build + test this workload
```

Deploy = Docker image → ACR → Foundry hosted agent. See `plan/15-agent-deploy-rest-api.md`
for the deploy REST flow. Container env below.

## Environment variables

| Var | Default | Purpose |
|-----|---------|---------|
| `FOUNDRY_PROJECT_ENDPOINT` | *(required)* | Foundry project endpoint (memory data plane). |
| `MODEL` | `gpt-5.4` | Chat deployment id (ignored by hosted container, but required by schema). |
| **`CHAT_CLIENT`** | `foundry` | `langchain4j` backs the framework `ChatClient` with a LangChain4j OpenAI-compatible model (ecosystem-bridge demo). |
| `LANGCHAIN4J_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible base URL (used when `CHAT_CLIENT=langchain4j`). |
| `LANGCHAIN4J_API_KEY` | *(unset)* | API key for the LangChain4j model. |
| `LANGCHAIN4J_MODEL` | `${MODEL}` / `gpt-5.4` | Model name for the LangChain4j model. |
| `AGENT_INSTRUCTIONS` | *(built-in)* | Override system instructions. |
| `WEB_SEARCH_CONTEXT_SIZE` | `medium` | `LOW`/`MEDIUM`/`HIGH`. |
| `CODE_INTERPRETER_ENABLED` | `true` | Attach the code interpreter tool. |
| `TODO_TOOL_ENABLED` | `true` | Attach the in-process todo tool. |
| `MIDDLEWARE_ENABLED` | `true` | Wrap runs with `MarkerMiddleware` (`AgentMiddleware`); inert unless a turn carries `MW_PING`. |
| `MEMORY_STORE_NAME` | `memstore-hostagent` | Foundry memory store. |
| `MEMORY_SCOPE` | `demo-user` | Default memory partition. |
| `MEMORY_UPDATE_DELAY_SECONDS` | `1` | Memory write debounce. |
| `MEMORY_MAX_RECALL` | `5` | Max recalled memories. |
| **`MCP_ENABLED`** | *(unset)* | `true` turns on the local-MCP demo (see below). |
| `MCP_STDIO_COMMAND` | `uvx` | MCP server launcher (stdio path). |
| `MCP_STDIO_ARGS` | `mcp-server-git,--repository,/opt/demo-repo` | Server + args (CSV). |
| `MCP_ALLOWED_TOOLS` | 7 read git tools | Allow-list (CSV); empty = all tools. |
| `MCP_HTTP_URL` | *(unset)* | If set, use a streamable-HTTP MCP server instead of stdio. |
| **`SKILLS_ENABLED`** | *(unset)* | `true` turns on the Agent Skills demo (see below). |
| `SKILLS_DIR` | `/opt/skills` | Directory scanned for `*/SKILL.md` skills. |

## Local MCP (git) demo — spans code + Docker + env

Optional, **off by default**. Demonstrates the framework acting as an **MCP client**: it spawns a
read-only [`mcp-server-git`](https://github.com/modelcontextprotocol/servers/tree/main/src/git)
over stdio and exposes its git read tools to the model as `FunctionTool`s. Three touchpoints must
stay in sync:

**1. Env** — flip on with `MCP_ENABLED=true`. Defaults point at the baked repo and the 7 read-only
git tools; override via the `MCP_*` vars above. `MCP_HTTP_URL` switches to a remote HTTP server.

**2. Code** — `AgentConfiguration.java`:
- `@Bean(destroyMethod="close") @ConditionalOnProperty(name="MCP_ENABLED", havingValue="true")
  McpToolSource mcpToolSource(...)` — reads the `MCP_*` env and returns
  `McpToolSource.stdio(...)` (or `.streamableHttp(...)`).
- `hostedAgent(...)` injects it via `ObjectProvider<McpToolSource>` and merges
  `mcp.listTools()` into the tools list (no-op when the bean is absent).
- All MCP protocol work lives in the **framework** (`framework/mcp` — `McpToolSource`): connect →
  `listTools` → **filter by allow-list** → adapt each to a `FunctionTool`. The allow-list is the
  read-only guarantee: write tools (`git_commit`, `git_add`, …) are never surfaced to the model.

**3. Docker** (`app/Dockerfile`):
- build stage adds `framework/mcp` to the Maven `-pl` reactor;
- runtime stage installs `git` + `curl` + `uv`, runs `uv tool install mcp-server-git` (so it runs
  with no runtime network), and bakes a synthetic `/opt/demo-repo` whose commits carry the unique
  marker **`ZTOKEN-9f3a1c`**. That marker exists nowhere on the public internet, so hosted
  web_search can't answer questions about it — the model is forced onto the git MCP tool. (All of
  this is inert unless `MCP_ENABLED=true`.)

**Why gated:** it's an optional demo with an external runtime dependency (`uv`/`git`/the baked
repo) and a startup cost (`listTools()` spawns the server eagerly). Off by default keeps the
container bootable everywhere and identical to the pre-MCP build unless explicitly enabled.

**End-to-end test:** `client/`'s `git-mcp` scenario asks for the HEAD commit message of
`/opt/demo-repo` and asserts the response contains `ZTOKEN-9f3a1c` — proving the git MCP tool ran.

## Agent Skills demo — spans code + Docker + env

Optional, **off by default**. Demonstrates the framework's `AgentSkillsProvider` (a second
`AIContextProvider`) implementing the [agentskills.io](https://agentskills.io/specification)
**progressive disclosure** pattern: skill names + descriptions (L1) are injected into the
instructions, and the model pulls a skill's full body on demand via `load_skill` (and resources via
`read_skill_resource`). Three touchpoints stay in sync:

**1. Env** — flip on with `SKILLS_ENABLED=true`. `SKILLS_DIR` (default `/opt/skills`) is scanned; each
subdirectory containing a `SKILL.md` is one skill.

**2. Code** — `AgentConfiguration.java`:
- `@Bean @ConditionalOnProperty(name="SKILLS_ENABLED", havingValue="true") AgentSkillsProvider
  skillsProvider(...)` returns `new AgentSkillsProvider(new FileSkillSource(SKILLS_DIR))`.
- `hostedAgent(...)` injects it via `ObjectProvider<AgentSkillsProvider>` and adds it as a **second**
  `aiContextProvider` alongside memory (no-op when the bean is absent).
- All skill logic lives in the **framework** (`framework/core` — `io.github.weidongxu.agentframework.skill`):
  frontmatter parse → L1 index → `load_skill`/`read_skill_resource` `FunctionTool`s. Read-only;
  `run_skill_script` is intentionally not implemented (sandboxing).

**3. Docker** (`app/Dockerfile`):
- bakes `app/skills` → `/opt/skills` (the `draft-changelog` skill);
- pins `GIT_AUTHOR_DATE`/`GIT_COMMITTER_DATE` so the baked `/opt/demo-repo` HEAD SHA is byte-for-byte
  reproducible — the skill returns that SHA and the e2e asserts a deterministic 40-hex pattern.

**Why gated:** optional demo; off by default keeps the container identical to the pre-skills build
unless explicitly enabled.

**End-to-end test:** `client/`'s `changelog-skill` scenario asks the agent to draft a changelog and asserts the
reply carries a 40-hex HEAD SHA + `ZTOKEN-9f3a1c` + a footer that exists only in the skill's L2 body
— proving `load_skill` ran and the skill read real git history.
