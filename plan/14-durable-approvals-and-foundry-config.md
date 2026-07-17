# 14 — Durable approvals + Foundry config

Closes the framework backlog opened after the 12 context providers landed. Two genuine gaps
implemented; the other three items were already done (audited).

## Durable tool approvals — `FileToolApprovalStore`

- New `core-impl/FileToolApprovalStore`: file-backed `ToolApprovalStore` alongside the default
  `InMemoryToolApprovalStore`.
- Mirrors InMemory semantics exactly: create / claim / complete / release / abandon / cleanup,
  fencing tokens, leases, `maxPendingRequests`.
- Each batch persisted as JSON under a base dir via atomic temp-file + move; in-memory index
  rebuilt from disk on construction. **Pending approval batches survive a process restart.**
- Reuses the ChatMessage/content JSON codec pattern from `FileSystemConversationStore`.
- Single-instance durability tier (filesystem analog of the in-memory default) — not shared
  across replicas. For multi-replica, back with Redis/Cosmos later.
- 7 hermetic tests incl. a restart/round-trip durability test.

## Foundry auth/config — `FoundryConfig`

- New `foundry/FoundryConfig`: parses `FOUNDRY_PROJECT_ENDPOINT`, `FOUNDRY_CREDENTIAL`
  (`default` / `azure_cli` / `managed_identity`), `FOUNDRY_MANAGED_IDENTITY_CLIENT_ID` — or an
  explicit builder — and selects the `TokenCredential`, then builds a `FoundryClientFactory`.
- Removes hardcoded `DefaultAzureCredentialBuilder` + endpoint env read from workloads; the
  `app/` workload now wires via `FoundryConfig.fromEnvironment()`.
- 8 hermetic tests (parsing, credential-type resolution, credential/factory construction); no
  Azure calls.

## Audited already-done (no new code)

- **progressive-tools** — wired end-to-end via `LiveToolRegistry` + `FunctionInvocationContext`.
- **workflow-foundation** — Sequential / Concurrent / Handoff / GroupChat all implemented +
  tested (YAML-declarative + durable checkpoints remain out-of-scope).
- **langchain4j-adapter** — adapts `ChatModel` + `StreamingChatModel`, tools, streaming.
