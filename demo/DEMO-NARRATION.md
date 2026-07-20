# Demo narration — "One hosted agent, two ways"

Spoken track for `demo.pptx` (7 slides). Target **~60–75s**, hard cap **2 min**. Don't read the
slide text — the voice adds the *why*. Word counts are the spoken line only.

---

**Slide 1 — Title** (~8s)

Same photography-assistant agent, same Foundry wire contract, built two ways. Let's walk the code.

**Slide 2 — The demo app** (~10s)

One rule decides every turn: a photo goes to photo-process, everything else is a normal chat. Identical on both sides — so all that changes is what you write around it.

**Slide 3 — Wire contract** (~10s)

The REST endpoint, readiness, named SSE, the response envelope — with the framework you write none of it. Without, that's a couple hundred lines you own and keep correct.

**Slide 4 — Streaming relay** (~12s)

Streaming is one line: the framework relays the model's frames for you. By hand it's the whole loop — and you even have to strip the `[DONE]` sentinel, because Chat-Completions sends it but the Responses contract forbids it.

**Slide 5 — Routing** (~10s)

Here both sides genuinely write code — it's the same decision. This is the app logic; everything else on the right is plumbing that shouldn't exist.

**Slide 6 — Add memory** (~10s)

Now add durable memory. One line attaches a provider. Without it, three hundred-plus lines — scope, recall, extraction, every gotcha — and you're on the hook for all of them.

**Slide 7 — …just middleware** (~10s)

And this was one seam. Same demo: roughly five hundred lines against a thousand. The framework carries the rest — providers, tools, workflows, hosting. It's a whole programming model.

---

**Total ≈ 70s.** To hit 60s, trim slide 4's `[DONE]` aside and slide 5.
