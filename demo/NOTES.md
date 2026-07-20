# Hosting a Foundry agent by hand — the iceberg below the demo

The `without-framework` project implements the visible wire contract. Real production hosting (per
hard-won lessons) carries much more. The framework is meant to absorb all of this.

## Wire contract (what the demo actually does)

- `GET /readiness` (+ `/healthz`, `/`) → 200 on port **8088**. The platform health-probes before
  routing; fail this and you get no traffic.
- Every SSE frame is **named**: `event: <type>` then `data: <json>`. Unnamed frames are dropped.
- Terminate the stream on `response.completed` / `.incomplete` / `.failed` — **never** a
  `data: [DONE]` sentinel. Getting the name or the terminator wrong yields `streaming_incomplete`.
- Streaming chat = **relay the model's own SSE frames verbatim**. The model's Responses stream is
  already named and already ends on `response.completed`, so a pass-through is fully compliant.
  (Defensive: drop any stray `data: [DONE]` an upstream gateway appends.)
- Open the upstream streaming call **before** writing SSE headers, so a non-2xx model response
  surfaces as a clean error instead of a half-written stream.

## Production burden the demo omits (framework should own it)

- **TLS egress via proxy** — outbound model calls go through a proxy whose CA must be imported into
  the container trust store, or every HTTPS call fails.
- **Three identities / RBAC** — the container, the caller, and the model each authenticate
  separately; wiring managed identity + role assignments is required, not optional.
- **Protocol 2.0.0 headers** — `x-agent-user-id`, `x-agent-foundry-call-id` (renamed from earlier
  drafts); read/echo them or lose correlation.
- **Per-user memory isolation** — partition all stored state by `x-agent-user-isolation-key`; never
  share memory across users. In the demo this is the without-framework `MemoryService` (241 lines) +
  ~99 lines of controller wiring: scope resolution (the low-level memory API resolves no identity),
  recall→instructions, async `remember`, a secret guard (no data-plane redaction), a pleasantry
  filter (extractor bloat), and the explicit `type:"message"` shape the memory parser requires — plus
  reassembling the streamed assistant text from the frames being relayed. The framework side is one
  line: `.aiContextProvider(memoryProvider)`.
- **Conversation continuity, retries, timeouts, backpressure, structured errors** — all hand-rolled
  without a framework.

The `with-framework` project gets the wire contract for free and has a clear home for the rest; the
`without-framework` project is where each of these becomes another hand-written file.
