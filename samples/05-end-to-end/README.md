# 05 — End-to-end

Full applications and demos that combine multiple framework features, mirroring MAF's
`05-end-to-end/`.

> **Status:** one self-contained scenario implemented (runnable). Requires `OPENAI_API_KEY`.

| Sample | Combines | Status |
|--------|----------|--------|
| [`TravelAssistant`](src/main/java/io/github/weidongxu/agentframework/samples/e2e/TravelAssistant.java) | function tool + memory (`FileMemoryProvider`) + multi-turn `AgentSession` | ✅ |

`TravelAssistant` stores a traveler preference on turn 1, then books a flight on turn 2 — recalling
the preference and calling the `book_flight` tool. Run it:

```powershell
$env:OPENAI_API_KEY = "sk-..."
mvn -q -f samples\05-end-to-end\pom.xml compile exec:java
```

For a **deployed** end-to-end (a hosted service invoked over the wire), the existing `app/` (hosted
agent: web search + memory + tools/skills) paired with `client/` (invokes the deployed agent and
verifies its features) is the reference.
