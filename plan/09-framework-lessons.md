# 09 — Framework-realm lessons (framework + its workloads)

Operational gotchas from deploying/running the **framework-based** app and its workloads
(`app/`, `client/`, `admin/`).

## 1. Model deployment **capacity** — the silent web-search killer

Hardest bug of the openai-RG deploy, and it was **not code**: the `gpt-5-4` deployment had
`--sku-capacity 1` (≈ 1000 TPM / 10 RPM).

- **Symptom:** simple 1-shot prompts worked; **web-search** turns returned **500**. Web search feeds
  ~4,400 tokens of results back into the model → exceeds the tiny TPM budget → model **429**, surfaced
  to the caller as **500**. The size-dependence is what misdirects diagnosis.
- **Fix:** raise capacity —
  `az cognitiveservices account deployment create ... --sku-name GlobalStandard --sku-capacity 50`.
  There is **no `deployment update` verb**; `create` is idempotent and updates in place.
- **Gotcha:** a capacity change **re-provisions** the deployment → a transient
  `404 "The API deployment for this resource does not exist … within the last 5 minutes"` for a few
  minutes. Wait it out; not a code error.
- Check `az cognitiveservices usage list` for the model family's quota before bumping.

## 2. Local JVM auth — use `AzureCliCredential`, not `DefaultAzureCredential`

Running `client` / `admin` / `Bootstrap` locally, `DefaultAzureCredential` can fail with every
credential reported "unavailable" **even when `az login` is valid**.

- **Root cause:** `DefaultAzureCredential` honours the `AZURE_TENANT_ID` env var and forwards it as
  `az account get-access-token --tenant <id>`. If that tenant differs from the active `az` session
  (dev box where `AZURE_TENANT_ID` points at a corp tenant while `az` is logged into a personal one),
  the CLI errors and azure-identity masks it into the generic "run az login" message.
- **Fix (what `client` uses):** a standalone `new AzureCliCredentialBuilder().build()` — it **ignores
  `AZURE_TENANT_ID`** and follows the active `az` session. The container is unaffected (it uses its
  managed identity, not the CLI).
- Secondary trigger: a cold `az` token cache (fresh AAD round-trip > azure-identity's ~10s subprocess
  timeout); a shell `az account get-access-token` warms it.

## 3. Deployment-name dashes: `MODEL` must match the *deployment* name exactly

The app reads `@Value("${MODEL:gpt-5.4}")`. The **deployment** is named `gpt-5-4` (dashes) while the
underlying model id is `gpt-5.4` (dot). Passing the dotted default → `404 "deployment does not exist"`.
Always set `MODEL` (and Bootstrap's `CHAT_MODEL`) to the **deployment name**, dashes and all.

## 4. Workload layout: `Bootstrap` lives in `admin`, not `app`

`Bootstrap` (one-time memory-store creator) is a memory-store admin op, so it lives in the **`admin`**
module (`com.example.memadmin.Bootstrap`) alongside `AdminCli` — run with dev/CI credentials,
**never inside the container**. `app` keeps only runtime classes; throwaway probes are not committed.

## 5. Memory salience is a **server-side** job — align with .NET, don't fork the provider

The app once shipped a forked `GatedFoundryMemoryProvider` (and, briefly, a `MemoryGates` filter
set) reimplementing/duplicating salience policy on the client. That's the wrong layer.

- **Official design (checked against `microsoft/agent-framework`):** Python's `FoundryMemoryProvider`
  only role-filters; .NET adds optional per-side message-filter hooks via
  `FoundryMemoryProviderOptions`. **Neither has a client-side memory client abstraction, and neither
  ships secret/pleasantry policy** — the extractor decides salience. Steer it with the store's
  `user_profile_details` (an extractor instruction) set at **store creation**; pleasantries yield no
  memory implicitly.
- **What we did:** gave Java's `FoundryMemoryProvider` the same `FoundryMemoryProviderOptions` (filter
  hooks + `contextPrompt` + null-safe `scopeResolver`, and it now skips the update when the combined
  filtered list is empty). Then the app went **fully server-side**: deleted its policy code entirely
  and wires only scope + `maxMemories` + `updateDelaySeconds`; `Bootstrap` sets `user_profile_details`
  as the sole guard. The client-side filter hooks remain available for workloads that want a
  belt-and-suspenders layer, but the demo app deliberately skips them to show the framework carrying
  the weight.
- **Client seam note:** Java added a `FoundryMemoryClient` interface + `SdkFoundryMemoryClient` impl
  that .NET/Python lack (they call the SDK project client directly). Keep it — it makes the provider
  unit-testable with a fake — but hide it behind `FoundryClientFactory.createMemoryClient(...)` so the
  workload never names the `azure-ai-agents` SDK type, matching the other-language app surface.
