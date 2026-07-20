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

## Phased plan (no code yet)

1. **Native develop spike** — Java `ProcessBuilder` → rawtherapee-cli + `pp3`; proves steps 2 & 4
   offline.
2. **Multimodal-in** — `DataContent`/`UriContent` in core; `contentText`/`mapMessage` accept
   `input_image`/`input_file`.
3. **Vision advice** — sub-call sends baseline JPEG, returns JSON params (structured output).
4. **Multimodal-out** — image content or URL; wire the app pipeline; retire `TodoTool`.

Also update `Dockerfile` (add rawtherapee + exiftool) and confirm container sizing.
