# 17 — PPT plan: why the Java Agent Framework

**Purpose.** Slide-by-slide outline for a pitch deck on the framework's advantages, using the
hand-rolled hosted agent (`weidongxu-microsoft/foundry-java-hosted-agent`, the `app/` module) as
the **"before"** baseline. ~12 slides, ~20 min. One idea per slide; speaker notes in italics.

**Core thesis.** *The same Foundry hosted agent that took ~2,500 lines of hand-written protocol,
tool-loop, and memory plumbing becomes a few dozen lines of configuration against a reusable,
tested framework — with parity to Microsoft Agent Framework (MAF) .NET/Python.*

---

## Baseline facts (cite on relevant slides)

- **Before** = `foundry-java-hosted-agent/app/` — hand-rolled Spring Boot app, **~2,482 lines** of
  Java: `ResponsesController` **1,143** (manual Responses SSE wire protocol + agentic tool-call
  loop), `MemoryService` **525**, `TodoTool` **419**, plus `FoundryClients`/`Bootstrap`/etc.
  Everything bespoke: SSE framing, `previous_response_id` threading, tool re-call loop, memory
  splice-into-instructions — all copy-pasted per project, untested as reusable units.
- **After** = this framework — **11 modules** (`core`, `core-impl`, `openai`, `langchain4j`,
  `mcp`, `foundry`, `observability-opentelemetry`, `workflows`, `agentserver-responses/-spring/
  -foundry`). Hosted agent = wire up `AgentResponseHandler` + `ResponsesEndpoint`; tools/memory/
  compaction/middleware are config, not code.
- Parity ledger: `plan/11-parity-matrix.md`. Samples: `plan/16` (21 runnable classes, 5 categories).

---

## Slide deck

1. **Title** — "A Java Agent Framework for Azure AI Foundry." Subtitle: the Java counterpart to
   Microsoft Agent Framework (.NET/Python). *One line: bring MAF's programming model to Java.*

2. **The problem** — Foundry ships MAF for .NET & Python only. Java teams hand-roll the hosted
   `/responses` protocol per project. *Show the 2,482-line "before" app as Exhibit A.*

3. **Before: one giant controller** — architecture of the hand-rolled app: a 1,143-line
   `ResponsesController` owns SSE framing, the tool-call loop, memory splicing, hosted-tool wiring.
   *Everything is entangled; nothing is reusable or independently tested.*

4. **After: the framework** — module map (core → impl → providers → agentserver). The hosted agent
   shrinks to configuration. *Same behavior, now a supported product surface.*

5. **Side-by-side: build a hosted agent** — left: excerpt of manual SSE + tool loop; right:
   `AgentResponseHandler(agent, mapper)` + `ResponsesEndpoint(handler)`. *Lines of code: hundreds → tens.*

6. **Separation of concerns** — providers are swappable behind interfaces: `ChatClient`
   (OpenAI ↔ LangChain4j), `ChatHistoryProvider`, `AIContextProvider`, `AgentMiddleware`. *Change a
   backend without touching agent logic — impossible in the monolithic controller.*

7. **Batteries included** — features you'd otherwise re-implement: function tools + agentic loop,
   agent-as-tool, structured output, MCP tools, agent skills, memory/context providers,
   **compaction**, **middleware**, durable tool approvals, conversation persistence.

8. **Multi-agent workflows** — `Sequential / Concurrent / Handoff / GroupChat` (the `workflows`
   module). *The hand-rolled app has no path to this; the framework makes it a builder call.*

9. **Observability & operations** — first-class OpenTelemetry module; durable approval store;
   cold-start-safe conversation persistence (`plan/12`, `plan/14`). *Production concerns handled once.*

10. **Parity with MAF** — walk the `plan/11` matrix (agents, chat pipeline, tools, memory, workflows,
    hosting) + the `samples/` suite (21 runnable classes mapped to MAF's `01`–`05`). *Not a toy —
    concept-by-concept alignment with the .NET/Python framework.*

11. **Testability & maintenance** — framework modules are unit-tested; the app becomes thin config.
    *Bug fixes land once in the framework, not re-patched across every hand-rolled agent.*

12. **Call to action / roadmap** — adopt for new Foundry Java agents; near-term: Maven Central
    release, remaining breadth (a2a, devui, declarative, eval harness, multimodal — see `plan/11`).
    *Ask: standardize Java Foundry agents on this framework.*

---

## Demo / backup slides (optional)

- **Live**: run a `samples/02-agents` class (e.g. `Middleware` or `ChatClients`) to show config-level
  feature swaps.
- **Numbers table**: LoC before/after per concern (protocol, tool loop, memory, backend swap).
- **Migration story**: this repo *is* the "before" app refactored into the framework — same
  workload, now a library (git history shows the migration).

## Talking-point anchors

- "Don't re-implement the Responses protocol — inherit it."
- "Swap OpenAI for LangChain4j with one config line."
- "Java gets MAF's model, not a fork of it — parity is the design goal."
