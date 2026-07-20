# Photo-process hosted-agent demo — framework vs. no framework

Two Spring Boot projects implement the **same** Foundry hosted agent: a **photography assistant**
whose middleware routes each turn:

- **Photo attached → crop mode.** Ask the vision model *"how should I crop this to make it most
  impactful?"*, get a strict-JSON rectangle, crop the JPEG with pure-JDK `ImageIO`
  (`PhotoProcessor`), return the cropped image as a `data:` URL — one atomic artifact.
- **No photo → chat mode.** A normal turn against the photography-assistant persona
  (`DEFAULT_INSTRUCTIONS`); the streaming path is a **real, verbatim relay** of the model's own SSE
  frames. **Durable per-user memory** augments this path — relevant facts are recalled into the
  instructions before the turn and the exchange is remembered after.

Same workload, same flow, same wire contract. The only difference is who writes the *hosting +
protocol + memory* layer.

## What the framework removes

| Concern | `with-framework` | `without-framework` |
|---|---|---|
| Serve `POST /responses`, `/readiness` | framework beans | `PhotoProcessController` (hand-written) |
| Named SSE lifecycle, no `[DONE]` | framework | `PhotoProcessController` (hand-written) |
| Model transport (Responses call, streaming relay) | injected `ChatClient` | `ModelClient` (hand-written) |
| Request parse + response object shaping | framework | `ResponsesJson` + `Attachment` (hand-written) |
| **Durable memory** (recall + store, scope, gating) | `FoundryMemoryProvider` — **one line** | `MemoryService` (241) + ~99 lines wiring |
| **App logic** (crop workflow) | `PhotoProcessMiddleware` | `PhotoProcessWorkflow` |
| Wiring | `PhotoProcessConfiguration` | `PhotoProcessController` ctor |

**App logic is comparable on both sides.** Everything else exists only in the without-framework
project: ~500 lines of hosting/protocol plumbing (`PhotoProcessController` + `ModelClient` +
`ResponsesJson` + `Attachment`) plus **~340 lines** of memory orchestration (`MemoryService` + the
controller's scope resolution, recall/store wiring, and streamed-output capture) — versus a
**one-line** `.aiContextProvider(memoryProvider)` on the framework side. See `NOTES.md` for the
production burden this plumbing must get right (the "iceberg" below the wire contract).

## Run

Both listen on **8088** and speak the Foundry `/responses` protocol. Set the model env vars, then:

```powershell
# framework project (build+install the framework first: mvn -q install at repo root)
mvn -q -f demo\photo-process-with-framework\pom.xml spring-boot:run

# no-framework project (framework-free, but uses the Foundry memory SDK directly)
mvn -q -f demo\photo-process-without-framework\pom.xml spring-boot:run
```

Env: `MODEL`, `OPENAI_BASE_URL`, `OPENAI_API_KEY`, optional `AGENT_INSTRUCTIONS`
(overrides the default photography-assistant persona). Memory is opt-in via
`FOUNDRY_PROJECT_ENDPOINT` (+ `MEMORY_STORE_NAME`, `MEMORY_SCOPE`); it stays disabled when unset.

A companion **code-walk deck** (`DEMO-DECK.md` → `demo.pptx`) narrates the comparison slide by slide.
