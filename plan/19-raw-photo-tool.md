# 19 — RAW-photo tool (feasibility + design)

Replace the demo `TodoTool` in `app/` with a real workload: develop a user-attached camera **RAW**
into an **adjusted JPEG**, using gpt-5.4 (vision) to pick the adjustments.

## Target workflow

1. User attaches a RAW in the conversation.
2. Develop **baseline** JPEG (no adjustment).
3. Send baseline JPEG to the model → JSON adjustment params (WB, tint, highlight, shadow, curve).
4. Develop **adjusted** JPEG with those params.
5. Return the adjusted JPEG to the user.

Steps 2 and 4 are the **same op**, different params.

## Verdict

**Feasible, but not a drop-in `FunctionTool` swap.** Imaging is doable in Java (orchestrating a
native RAW developer). The *workflow* needs **3 multimodal capabilities the framework lacks today**
(all under the known "multimodal" gap):

- **in:** carry `input_file`/`input_image` bytes to the agent;
- **model-send:** map an image to `input_image` so gpt-5.4 can see the JPEG;
- **out:** return an image (content or URL) to the user.

## Code facts (why it's not a drop-in)

- **Tool contract is text-only.** `FunctionTool` handler is
  `(Map<String,Object> args, ToolContext) → CompletionStage<String>` (`app/TodoTool.java`,
  `core/tool/FunctionTool.java`). Returns a **String**; `ToolContext` exposes only `AgentSession`
  (`core/tool/ToolContext.java`) — no request attachments, no binary out.
- **Input is flattened to text.** `AgentResponseHandler.contentText()`
  (`agentserver-foundry/AgentResponseHandler.java:552`) keeps only `text` parts; any
  `input_image`/`input_file` part is dropped.
- **Model client sends text only.** `OpenAIResponsesChatClient.mapMessage()`
  (`openai/OpenAIResponsesChatClient.java:158`) handles Text/FunctionCall/FunctionResult and
  **throws** on anything else.
- **No binary content type.** `ChatContent` subtypes are Text / FunctionCall / FunctionResult /
  ToolApproval (`core/chat/*Content.java`).
- **Vision-in is available in the SDK.** OpenAI Java SDK ships `ResponseInputImage` /
  `ResponseInputFile` / `ResponseInputContent`; gpt-5.4 is multimodal → model-send is a small add.
- **Runtime can host native tools.** `app/Dockerfile` base is `eclipse-temurin:21-jre` (Debian) →
  `apt-get` a RAW developer.

## RAW→JPEG in Java

No robust **pure-Java** RAW decoder for modern cameras. Realistic path = **Java orchestration of a
native CLI** via `ProcessBuilder` + a temp working dir.

| Tool | WB/tint | highlight/shadow | tone curve | Weight | Notes |
|---|---|---|---|---|---|
| **rawtherapee-cli** | ✅ | ✅ | ✅ | med | `pp3` sidecar = exact param map. Recommended. |
| **darktable-cli** | ✅ | ✅ | ✅ | heavy (~1GB) | XMP sidecar; strongest pipeline. |
| LibRaw/dcraw + ImageMagick | ✅ | ✅ (basic) | via 2nd IM stage | light | curves harder. |

Recommendation: **rawtherapee-cli** driven by a generated `pp3`. Add `exiftool` for metadata.

## Architecture (the hard part: bytes in/out)

A model-invoked function tool is a **poor fit** — the model can't pass a RAW as a function arg
(binary; and it can't read RAW), and a tool can't emit an image. Two viable designs:

- **(Recommended) App-owned pipeline, not a model tool.** A request-level component detects a RAW
  attachment and runs *develop-baseline → vision-advice sub-call → develop-adjusted → return JPEG*.
  The model is used only for the advice sub-call. Needs multimodal **in** + attachment access +
  multimodal **out**.
- **Tool + side-channel.** Model calls `develop_photo(params)`; bytes travel out-of-band via an
  attachment-id → byte-store keyed on the session. More plumbing; same 3 gaps.

Parity target for the content types: MAF's `DataContent` (inline bytes + media type) and
`UriContent` (reference) in `Microsoft.Extensions.AI`.

## Foundry platform caveats

- **Payload size:** inline base64 RAW (~27–67MB) may exceed Responses/gateway limits → prefer a
  blob/SAS URL the container downloads, but container egress is proxied — verify reachability.
- **Compute/latency:** RAW development is CPU/RAM heavy → size the container; seconds per develop.

## Phased plan

1. **Native develop spike** — ✅ **Done** (`photo/` lib). Java `ProcessBuilder` → `rawtherapee-cli`
   + generated `pp3`; baseline + adjusted develop proven locally (see below).
2. **Multimodal-in** — ✅ **Done**. Core `DataContent` (bytes + media type + name); the Foundry
   `AgentResponseHandler` parses `input_file` (base64 `file_data`) / `input_image` (data-URL
   `image_url`) into `DataContent` on the user message, plus a text breadcrumb naming the
   attachment. `OpenAIResponsesChatClient` skips `DataContent` (a text model can't consume a RAW),
   so the bytes never go upstream.
3. **App pipeline + multimodal-out (item #1, neutral)** — ✅ **Done**. `RawDevelopMiddleware`
   (agent middleware) detects a camera-RAW `DataContent`, develops a **neutral** JPEG downscaled to
   `PHOTO_MAX_LONG_EDGE_PX` (default 2048), and returns it as a `data:image/jpeg;base64,…` URL
   inside `output_text` — the gateway-safe delivery. It **short-circuits the model** (buffered +
   streaming). Wired in `AgentConfiguration` (default on, `PHOTO_ENABLED`); `TodoTool` retired
   (default off). Dockerfile runtime installs `rawtherapee`.
4. **Vision advice (item #2/#3)** — TODO. Sub-call sends the neutral JPEG (≤1024px long edge is
   enough), returns `DevelopSettings` JSON, re-develop adjusted. Builds on the same middleware seam.

Deploy: re-enable the Foundry agent, `az acr build` the image, verify item #1 end-to-end. Watch the
inbound request size — a full RAW as base64 `input_file` (~32MB) may hit the gateway limit; use a
smaller RAW or a stored-blob fallback if rejected.

## Gateway request-size limit + blob-SAS delivery (verified on hosted agent)

- **Finding:** the Foundry `responses` gateway caps inbound request bodies at **~18–19 MB**
  (probed: 17.3 MB body forwarded, 20 MB body → 400). A real RAF (22–32 MB) as inline base64
  `input_file.file_data` = ~30 MB body → the gateway rejects it (502/400) before the container
  is reached. Inline base64 delivery of a full RAW is therefore **not viable** over the gateway.
- **Solution (shipped):** `input_file`/`input_image` may instead carry an `http(s)` **`file_url`**
  (e.g. an Azure Blob **SAS URL**). `AgentResponseHandler.fetchUrl` downloads it **server-side**
  into `DataContent` (capped at 128 MB, 15 s connect / 120 s read timeout). The inbound request
  stays tiny (~0.5 KB), so the gateway limit is a non-issue.
- **Verified end-to-end on the hosted agent (v11):** uploaded a 32.5 MB `.RAF` to blob
  `stweidxumeasar51/raw-uploads`, sent a 0.5 KB `file_url` request → container downloaded the RAF
  (**egress to Azure Blob works**), developed it, and returned a valid **729.5 KB / 2048×1366**
  neutral JPEG as a `data:` URL in 32 s. **Item #1 done on Foundry.**
- Container egress reaching Azure Blob confirms the earlier plan/19 "egress is proxied" caveat does
  not block outbound HTTPS to blob SAS URLs in this setup.

## `photo/` lib (phase 1, done)

Standalone Maven module `io.github.weidongxu:raw-photo` (independent build, Java 17); `app` depends
on it as a binary artifact. The Dockerfile build stage installs it before packaging `app`; the
runtime image installs `rawtherapee` (provides `rawtherapee-cli`).

- `DevelopSettings` (+ builder + `fromJson`) — editor-neutral params: WB temp, tint, exposure EV,
  contrast, saturation, highlight/shadow recovery, tone-curve points, and `maxLongEdgePx` (output
  downscale to a max long edge, aspect preserved, no upscaling). `neutral()` = baseline; `fromJson`
  maps the vision step's JSON.
- `Pp3Writer` — renders settings → RawTherapee 5.12 `pp3` (WB → `[White Balance]`,
  exposure/contrast/saturation/curve → `[Exposure]`, highlight/shadow → `[Shadows & Highlights]`,
  `maxLongEdgePx` → `[Resize]`). Neutral → no profile (baseline needs no `-p`).
- `RawDeveloper` / `RawTherapeeDeveloper` (+ `RawTherapeeOptions`) — invokes
  `rawtherapee-cli -o <out> [-p <pp3>] -j<q> -Y -c <in>` via `ProcessBuilder`; CLI path from
  `RAWTHERAPEE_CLI` env or PATH; succeeds only on exit 0 + non-empty output.

### Local evaluation (verified)

- CLI: RawTherapee 5.12 `rawtherapee-cli.exe`; sample: Fujifilm `.RAF` (~24MB).
- `Pp3WriterTest` (5 unit tests) — always runs, no native dep.
- `RawTherapeeDeveloperIT` — gated on `RAWTHERAPEE_CLI` + `RAW_SAMPLE` env; **passed**: baseline
  (neutral) + adjusted (WB 4800K / tint / exposure / contrast / S&H / curve) both produced valid,
  distinct JPEGs. ~3.6s baseline, ~4.2s adjusted. Confirms RAF decode + full adjustment pipeline
  (WB, tint, highlights, shadows, tone curve) works from Java.
