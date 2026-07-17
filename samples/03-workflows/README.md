# 03 — Workflows

Workflow orchestration patterns, mirroring MAF's `03-workflows/`. Backed by `framework/workflows`.

> **Status:** four patterns implemented (runnable). All require `OPENAI_API_KEY`
> (`OPENAI_MODEL` optional, default `gpt-4o-mini`).

| Pattern | Sample | Framework surface | Status |
|---------|--------|-------------------|--------|
| sequential | [`SequentialSample`](src/main/java/io/github/weidongxu/agentframework/samples/workflows/SequentialSample.java) | `workflows/SequentialWorkflow` | ✅ |
| concurrent | [`ConcurrentSample`](src/main/java/io/github/weidongxu/agentframework/samples/workflows/ConcurrentSample.java) | `workflows/ConcurrentWorkflow` | ✅ |
| handoff | [`HandoffSample`](src/main/java/io/github/weidongxu/agentframework/samples/workflows/HandoffSample.java) | `workflows/HandoffWorkflow` | ✅ |
| group-chat | [`GroupChatSample`](src/main/java/io/github/weidongxu/agentframework/samples/workflows/GroupChatSample.java) | `workflows/GroupChatWorkflow` | ✅ |

Run a sample by its main class:

```powershell
mvn -q -f samples\03-workflows\pom.xml compile ^
    exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.workflows.HandoffSample
```

**N/A for now** (see `plan/11`): declarative (YAML) workflows, checkpoints / durable execution.
