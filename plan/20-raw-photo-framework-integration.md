# RAW-photo tool — how the framework empowers it

A real hosted-agent workload: user attaches a camera **RAW**, gets back an **AI-adjusted JPEG**.
This doc is the *framework-integration* view — what the Java Agent Framework does so the feature
is ~one middleware + one library, not a hand-rolled server. (Design/feasibility: `plan/19`.)

## What the feature does

1. Neutral develop (RAW → JPEG, RawTherapee CLI) — the baseline.
2. Vision sub-call: a small preview → gpt-5.4 returns adjustment values (WB, exposure, contrast,
   highlights/shadows, saturation, …) as JSON.
3. Re-develop with those settings + **auto lens correction** (Lensfun), return a `data:` JPEG.

## Framework building blocks it leans on

| Framework capability | What it saves the workload | Where |
|----|----|----|
| **Agent middleware pipeline** (`AgentMiddleware`) | Intercept the run, act on the RAW, and **short-circuit the model** (`FinishReason.STOP`) — no prompt engineering to "make the model develop a photo". | `RawDevelopMiddleware` |
| **Multimodal `DataContent`** (in + out) | RAW arrives as a typed attachment; the JPEG goes back as a `data:` URL — no manual base64/part plumbing. | attachment in → `data:` out |
| **`ChatClient` abstraction** | The middleware makes a **nested vision call** through the *same* client the agent uses — model-agnostic, no second SDK. | `adviseSettings()` |
| **`OpenAIResponsesChatClient`** | Maps preview `DataContent` → `input_image` (vision-in) and enforces `type:"message"`; requests strict JSON via `ResponseFormat.jsonObject()`. | vision-in mapping |
| **Agent hosting** (`agentserver-*`) | Serves the Foundry `/responses` protocol, sessions, and streaming. The feature ships as a **bean + env config**, not an HTTP server. | `AgentConfiguration` |

## Integration shape

```
Foundry /responses ─▶ AgentServer ─▶ [ RawDevelopMiddleware ] ─▶ (model, skipped)
                                          │  RAW attached?
                                          ├─ no  ─▶ next.invoke(context)   (normal chat)
                                          └─ yes ─▶ photo lib (neutral preview)
                                                    └─ ChatClient vision sub-call ─▶ settings JSON
                                                       └─ photo lib re-develop (+lens) ─▶ data: JPEG
```

- **Detection & short-circuit:** `findRaw(context)` scans `DataContent`; on a hit the middleware
  produces the JPEG and returns `STOP`, so the LLM never sees the binary.
- **Nested advice call:** built from framework `ChatMessage`/`ChatOptions`; the DEVELOPER-role
  prompt is fixed, so user text can't leak into the vision sub-call. Any failure → **graceful
  fallback** to a neutral develop (never errors the run).
- **Config, not code:** `PHOTO_LENS_CORRECTION` (default true), advice on/off, and output
  `maxLongEdgePx` (forced onto the result — the model can't dictate final resolution) are wired in
  `AgentConfiguration`.

## The part that is *not* framework

`photo/` — a plain Java library wrapping `rawtherapee-cli` (`Pp3Writer`, `DevelopSettings`,
`RawTherapeeDeveloper`). Deliberately framework-agnostic; the middleware is the only glue.

## Payoff

- **~one middleware + one lib.** No bespoke `/responses` server, no attachment/base64 handling, no
  separate vision SDK — all provided by the framework.
- **Model-swappable & testable.** The vision step is a `ChatClient`, so tests stub it and the model
  is config (`gpt-5-4`).
- **Verified live** on the hosted agent (v15): RAW SAS in → 1358×2048 adjusted JPEG out, note
  "AI-suggested adjustments … auto lens correction". See `plan/19` for the e2e/deploy details.
