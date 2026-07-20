# Demo deck — One hosted agent, two ways (code walk)

Companion outline for `demo.pptx`. 7 slides, ~2 min. Side-by-side code compares the **same**
photography-assistant hosted agent built **with** vs **without** the framework. Order goes from the
outer wire contract inward, then a second worked example (memory), then the full surface.

**Colour rule:** green = **with framework**, amber = **without framework** (left/right throughout).
Slide 2's two *modes* use blue/purple so they're never confused with the with/without split.

---

### Slide 1 — Title

**One hosted agent, two ways — a code walk**

Same photography-assistant agent, same Foundry `/responses` wire contract. This walk shows one
slice: the middleware + hosting seam. Left = with framework, right = hand-written.

---

### Slide 2 — The demo app (shared, same on both sides)

A **photography-assistant hosted agent**. Its middleware routes every turn:

- **[blue] Photo attached → photo-process mode** — ask the vision model *"what's the best way to
  process this photo for impact?"*, get a strict-JSON plan, apply it with pure-JDK `PhotoProcessor`.
- **[purple] No photo → chat mode** — a normal streaming turn against the assistant persona.

This app logic is **identical** on both sides (`PhotoProcessMiddleware` ≈ `PhotoProcessWorkflow`).
Everything that follows is what each side must write *around* it.

---

### Slide 3 — The wire contract (REST + SSE)

**With framework** — hosting is *inherited beans*, not code *(`PhotoProcessConfiguration`)*

```java
@Bean ResponsesEndpoint responsesEndpoint(..)   { return new ResponsesEndpoint(handler); }
@Bean HealthController  healthController()        { return new HealthController(); }        // /readiness
@Bean ResponseHandler   agentResponseHandler(..)  { return new AgentResponseHandler(..); }  // parse + SSE
```

**Without framework** — hand-write the whole protocol layer
*(`PhotoProcessController` + `ResponsesJson` 154 + `Attachment` 10; complex part = `writeSse`)*

```java
private void writeSse(HttpServletResponse http, String assistantText) {
    // every frame NAMED (event: <type>), stream ends on response.completed — never data: [DONE]
    writeFrame(writer, "response.created",           created);
    writeFrame(writer, "response.output_text.delta", delta);
    writeFrame(writer, "response.completed",         completed);
}
```

**Beat:** the REST/SSE contract, `/readiness`, and the response object all only exist on the right.

---

### Slide 4 — Streaming chat (the relay)

**With framework** — the entire streaming chat path *(1 line)*

```java
return next.invoke(context);   // ChatClient relays the model's own SSE frames internally
```

**Without framework** — hand-roll the verbatim relay: `openChatStream` + `relayChat`
*(~50 lines; complex part shown)*

```java
try (upstream) {
    upstream.forEach(line -> {
        if ("data: [DONE]".equals(line.trim())) return;  // Responses contract forbids [DONE]
        writer.write(line);
        writer.write("\n");
        if (line.isEmpty()) writer.flush();              // blank line ends an SSE frame
    });
}
```

**Beat:** framework side writes nothing; without it you own the streaming relay by hand.

---

### Slide 5 — The routing / middleware flow

**With framework** — `PhotoProcessMiddleware.invoke` *(14 lines, full)*

```java
DataContent photo = findPhoto(context);
if (photo == null) {
    return next.invoke(context);  // no photo → normal agent turn
}
return CompletableFuture.supplyAsync(
    () -> AgentResponse.builder().message(cropPhoto(photo))
            .finishReason(FinishReason.STOP).build(),
    executor);
```

**Without framework** — `PhotoProcessController.createResponse` *(routing branch)*

```java
if (photo != null) {              // photo-process
    String text = workflow.run(userText, photo);
    if (stream) writeSse(resp, text); else writeJson(resp, text);
    return;
}
// no photo → chat mode (memory-augmented, see next slide)
```

**Beat:** identical decision — the difference is everything each side writes around it.

---

### Slide 6 — Now add memory (a second worked example)

**With framework** — attach a provider: **one line**

```java
ChatClientAgent.builder(chatClient)
    .aiContextProvider(memoryProvider)   // ← recall-before + store-after, all handled
    ...
```

**Without framework** — hand-write the orchestration: `MemoryService` **241 lines** + ~99 lines of
controller wiring *(complex part shown)*

```java
// per-user scope, recall into instructions, remember after — none of it free:
String scope        = resolveScope(userIsolationKey, rawText);   // no identity resolution in the API
String instructions = computeInstructions(userText, scope);      // recall + splice
...
memory.remember(userText, assistant.toString(), scope);          // async extract, secret-guarded
// streaming path must ALSO reassemble assistant text from the frames it relays (captureDelta)
```

Plus the hard-won details `MemoryService` still owns: explicit `type:"message"` (data-plane parser),
secret guard (no redaction), pleasantry filter (extractor bloat), recall char-budget.

**Beat:** one line vs **~340 lines** — and every gotcha is on the right.

---

### Slide 7 — …and this was just middleware

You saw **one seam**. The framework carries the rest:

**This one demo: ~340 vs ~880 lines** — shared app logic aside, the framework removes ~540 lines of
plumbing (494 vs 1027 lines total across the two projects).

- **12** context/memory providers — Foundry memory, Agent Skills, file memory, compaction, todo,
  agent-modes, shell, file-access, background agents, code-act, message injection.
- **3** chat providers — OpenAI, Foundry (Azure AI), LangChain4j.
- **9** tool types — function, web search, code interpreter, file search, image gen, remote + local
  MCP, agent-as-tool, approvals.
- **4** multi-agent workflow patterns — sequential, concurrent, handoff, group-chat.
- **Hosting + OpenTelemetry** — Responses lifecycle, health, platform headers, graceful shutdown.

**Beat:** one middleware seam here — the framework is a whole programming model.
