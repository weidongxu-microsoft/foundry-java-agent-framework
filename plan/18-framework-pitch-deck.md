---
marp: true
title: A Java Agent Framework for Azure AI Foundry
paginate: true
theme: default
---

<!--
Marp deck source. Edit this markdown, then generate slides:
  npx @marp-team/marp-cli@latest plan/18-framework-pitch-deck.md -o pitch.pptx   # PowerPoint
  npx @marp-team/marp-cli@latest plan/18-framework-pitch-deck.md --pdf           # PDF
Each `---` starts a new slide. Speaker notes live in HTML comments and export to PPTX notes.
Code snippets are real, taken from samples/ (compile-verified). Outline: plan/17-framework-pitch-ppt.md.
-->

# A Java Agent Framework for Azure AI Foundry

### The Java counterpart to Microsoft Agent Framework (.NET / Python)

Bring MAF's programming model to Java — same concepts, first-class support.

<!-- 30-sec opener: Foundry ships MAF for .NET and Python only; Java teams are on their own. This framework closes that gap. -->

---

## The problem → the fix

- Foundry ships **MAF for .NET & Python only** — Java teams hand-roll the hosted
  `/responses` protocol per project.
- Baseline: a real hand-written Foundry agent = **~2,482 lines** of bespoke Java
  (a **1,143-line** controller owns SSE framing + tool loop + memory).

> **This framework:** that becomes a few dozen lines of **configuration** against
> **11 reusable, tested modules** — with parity to MAF .NET/Python.

<!-- One slide on the "before". The rest of the deck shows how little code you now write. -->

---

## Your first agent — the whole program

```java
// 1. Framework ChatClient over the OpenAI Responses protocol
ChatClient chatClient = new OpenAIResponsesChatClient(openai, executor);

// 2. Compose an agent — ChatClientAgent runs the model/tool loop for you
Agent agent = ChatClientAgent.builder(chatClient)
        .name("hello-agent")
        .instructions("You are a friendly assistant. Answer in one sentence.")
        .chatOptions(ChatOptions.builder().modelId(model).build())
        .build();

// 3. Run a turn
AgentResponse response = agent.run(question).toCompletableFuture().get();
System.out.println(response.getText());
```

<!-- samples/01-get-started/HelloAgent.java. That's the entire agent. No protocol code. -->

---

## Add a tool — the loop is handled

```java
FunctionTool weatherTool = new FunctionTool(
        "get_weather", "Get the current weather for a city.",
        weatherSchema(),                       // JSON-schema param map
        args -> CompletableFuture.completedFuture(lookup(args)));  // handler

Agent agent = ChatClientAgent.builder(chatClient)
        .instructions("Use get_weather when asked about the weather.")
        .chatOptions(ChatOptions.builder().modelId(model).build())
        .tools(List.of(weatherTool))
        .build();

agent.run("What's the weather in Seattle?");   // model calls the tool, framework
                                               // executes it, feeds result back, re-calls
```

<!-- samples/01-get-started/AddTools.java. The agentic tool-call loop is the framework's job, not yours. -->

---

## Cross-cutting logic — middleware

```java
Agent agent = ChatClientAgent.builder(chatClient)
        .instructions("You are a concise assistant.")
        .middleware(new TimingMiddleware())     // <-- attach
        .build();

final class TimingMiddleware implements AgentMiddleware {
    public CompletionStage<AgentResponse> invoke(
            AgentMiddlewareContext ctx, AgentMiddlewareNext next) {
        long start = System.nanoTime();
        return next.invoke(ctx).whenComplete((resp, err) ->
            log("run finished in " + (System.nanoTime() - start) / 1_000_000 + " ms"));
    }
}
```

<!-- samples/02-agents/Middleware.java. Logging, auth, retries, guardrails — wrap every run without touching agent logic. -->

---

## Multi-agent — a builder call

```java
SequentialWorkflow workflow = SequentialWorkflow.builder()
        .participant("outliner", outliner)
        .participant("writer", writer)
        .build();

WorkflowRunResult result = workflow.run(
        List.of(ChatMessage.user(topic)), new WorkflowSession())
    .toCompletableFuture().get();
```

Also: **`ConcurrentWorkflow` · `HandoffWorkflow` · `GroupChatWorkflow`** — same builder shape.

<!-- samples/03-workflows/SequentialSample.java. Orchestration the hand-rolled app had no path to. -->

---

## Host a Foundry agent — protocol inherited

```java
@Bean ChatClient chatClient(...) {
    return new OpenAIResponsesChatClient(openai, executor);
}
@Bean Agent hostedAgent(ChatClient c) {
    return ChatClientAgent.builder(c).instructions("...").build();
}
@Bean ResponseHandler handler(Agent a, ObjectMapper m) {
    return new AgentResponseHandler(a, m);       // adapts agent -> Responses
}
@Bean ResponsesEndpoint endpoint(ResponseHandler h) {
    return new ResponsesEndpoint(h);             // serves POST /responses
}
```

The **1,143-line controller** → a handful of `@Bean`s.

<!-- samples/04-hosting/HostingConfiguration.java. The SSE wire contract + tool loop are inherited from agentserver-*. -->

---

## Swap the backend — one line

```java
// OpenAI Responses ...
ChatClient client = new OpenAIResponsesChatClient(openai, executor);

// ... or LangChain4j, same Agent code above
ChatClient client = new LangChain4jChatClient(chatModel, streamingModel, executor);
```

Everything behind interfaces — `ChatClient`, `ChatHistoryProvider`, `AIContextProvider`,
`AgentMiddleware` — swap without touching agent logic.

<!-- samples/02-agents/ChatClients.java selects backend via CHAT_CLIENT env. Provider independence the monolith can't offer. -->

---

## Batteries included

Supported, tested modules — not per-project glue:

- Function tools **+ agentic loop**, agent-as-tool, structured output
- **MCP** tools, **agent skills**
- Memory / context providers, **compaction**, **middleware**, conversations
- Multi-agent **workflows**; **OpenTelemetry**; durable approvals; conversation persistence

<!-- Each maps to a runnable sample in samples/. Everything here is a config-level feature. -->

---

## Parity with MAF

Concept-by-concept alignment with .NET/Python — not a fork:

- **Feature ledger** (`plan/11`): agents, chat pipeline, tools, memory, workflows, hosting
- **Samples suite** (`plan/16`): **21 runnable classes**, 5 categories, mapped to MAF's `01`–`05`
- Java gets MAF's *model* — parity is the explicit design goal

<!-- The parity matrix is a living ledger updated per feature. Samples mirror MAF's numbered progression. -->

---

## Backup — the numbers

| Concern | Before (hand-rolled `app/`) | After (framework) |
|---------|------------------------------|-------------------|
| Responses protocol + tool loop | ~1,143 lines | inherited (a few `@Bean`s) |
| Memory | 525 lines bespoke | context-provider config |
| Backend swap (OpenAI→LangChain4j) | rewrite | one line |
| Multi-agent | not possible | builder call |
| Tests | none reusable | per-module unit tests |

<!-- This repo IS the before-app refactored into the framework — same workload, now a tested library. -->
