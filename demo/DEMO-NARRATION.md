# Demo narration — "One hosted agent, two ways"

Spoken track for `demo.pptx` (7 slides). Target **~60–75s**, hard cap **2 min**. Don't read the
slide text — the voice adds the *why*. Word counts are the spoken line only.

---

**Slide 1 — Title** (~8s)

Same photography-assistant agent, same Foundry wire contract, built two ways. Let's walk the code.

**Slide 2 — The demo app** (~6s)

A photo goes to photo-process; anything else is chat. Same on both sides — only the plumbing around it differs.

**Slide 3 — Wire contract** (~7s)

REST, readiness, named SSE, the response envelope — free with the framework, a couple hundred lines without.

**Slide 4 — Streaming relay** (~9s)

Streaming is one line. By hand it's the whole loop — you even strip the `[DONE]` sentinel, which Chat-Completions sends but the Responses contract forbids.

**Slide 5 — Routing** (~7s)

Both sides write this — it's the real decision. Everything else on the right is plumbing that shouldn't exist.

**Slide 6 — Add memory** (~8s)

Durable memory: one line to attach a provider, versus three hundred-plus — scope, recall, extraction, every gotcha on you.

**Slide 7 — …just middleware** (~8s)

And that was one seam: five hundred lines versus a thousand. The framework carries the rest — providers, tools, workflows, hosting.

---

**Total ≈ 55s.**
