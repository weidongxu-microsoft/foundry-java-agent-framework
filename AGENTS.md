# AGENTS.md

## Writing docs — be concise

Keep every doc (including this one, `plan/`, and any new `*.md`) short and to the point. Prefer
tight bullet points over prose, state only what matters, and cut filler. New documentation must
be concise.

## Goal

Build a **Java Agent Framework** — the Java counterpart to
[microsoft/agent-framework](https://github.com/microsoft/agent-framework), which today ships
only .NET and Python. This repo is where that Java framework is developed.

The framework is the product. The existing hosted-agent and client code in this repo are kept
as **test / reference workloads** that exercise the framework, not as the deliverable.

## Repo layout

- `app/` — Java (Spring Boot) hosted agent implementing the Foundry `/responses` protocol
  (web search + memory). Used as a test workload.
- `client/` — standalone Java console client that invokes a deployed agent end-to-end. Test
  workload.
- `admin/` — memory-store admin utility. Test workload.
- `plan/` — design notes, findings, and captured learnings (see `plan/README.md` index).
- `samples/` — progressive runnable examples (MAF-parity `01`–`05`); scaffold in progress.
- `.agents/skills/` — reusable Copilot skills for this repo.

## Conventions

- Framework core targets Java 11; test workloads may use Java 17/21. Maven, group id
  `io.github.weidongxu`; framework base package `io.github.weidongxu.agentframework`.
- Keep the framework code separate from the test workloads (`app/`, `client/`, `admin/`).
- The framework parent pom (root) aggregates **only** the `framework/*` modules. The workloads
  (`app/`, `client/`, `admin/`) are **independent builds** that depend on the framework as
  binary artifacts — they are not modules of the framework parent pom.
- Mirror the concepts and public shape of microsoft/agent-framework (.NET/Python) where it
  makes sense for Java — agents, tools, memory, and the hosted/client split.
- Do not commit secrets. `.memory/` and build output are gitignored.

## Design docs (`plan/`)

- All design notes, findings, and decisions go in **`plan/NN-topic.md`** (zero-padded, next
  sequential number). Keep them concise (see top).
- **`plan/README.md` is the living index** — add a row for every new `plan/*.md` you create.
- **`plan/11-parity-matrix.md`** is the feature-catalog parity ledger — update it on every
  "add/adjust feature X" request.

## Memory

- Use the **memory skill** (`.agents/skills/memory`) to store durable, non-sensitive facts,
  decisions, and conventions as markdown under `.memory/` (e.g. "save memory to `<name>`").
- Recall or search prior notes before starting related work (e.g. "recall memory `<name>`",
  "search memory `<query>`") to reuse existing decisions and conventions.
- `.memory/` is local-only (gitignored) — never save secrets, tokens, or credentials.

## Build & test

The framework is the product; workloads consume it as installed artifacts (not published to
Maven Central yet). Install the framework first, then build each workload independently.

```powershell
# 1. Framework reactor (the product) — build, test, and publish to the local ~/.m2 repo
mvn -q install                      # or: mvn -q -DskipTests install

# 2. Workloads — independent builds that resolve the framework from the local repo
mvn -q -f app\pom.xml test          # hosted-agent (Spring Boot fat jar → Docker)
mvn -q -f client\pom.xml compile    # console client
mvn -q -f admin\pom.xml compile     # memory-store admin utility
```

- `app/` deploys as a self-contained Spring Boot fat jar: its Docker build installs the
  framework in the build stage, then packages the app so the framework + all transitive deps
  are bundled — the runtime image needs no dependency resolution.
- No Maven Central release is required for local/CI/Docker builds; a registry release
  (GitHub Packages or Maven Central) can follow when the API stabilizes.

### Running tests — target the specific test

To save output tokens, **run the smallest targeted test**, not the full suite — a single test
class (`-Dtest=McpToolSourceTest`) or method (`-Dtest=McpToolSourceTest#allowListFiltersOutNonAllowedTools`).
Escalate to the full suite only when a targeted run shows it's needed.

```powershell
mvn -q -f framework\mcp\pom.xml -Dtest=McpToolSourceTest test        # one class
mvn -q -f app\pom.xml -Dtest=TodoToolTest#writeReplacesList test     # one method
```

The same applies to end-to-end tests (e.g. in `client/`): run the single e2e test that exercises
the change, not every workload.

## References

- Framework parity target: https://github.com/microsoft/agent-framework
- Foundry hosted agents: https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/hosted-agents
