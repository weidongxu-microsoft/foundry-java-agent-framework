---
marp: true
title: Java Agent Framework — Leadership One-Pager
paginate: false
theme: default
style: |
  section { font-size: 22px; padding: 48px 60px; }
  h1 { font-size: 34px; margin-bottom: 4px; }
  p { margin: 10px 0; line-height: 1.35; }
---

<!--
One-slide leadership deck. Render:
  npx @marp-team/marp-cli@latest plan/19-leadership-onepager.md -o onepager.pptx
Audience: leadership. No code — business gap, what we built, proof, ask.
-->

# A Java Agent Framework for Azure AI Foundry

**The Java counterpart to Microsoft Agent Framework (.NET / Python).**

**The gap** — Foundry ships the agent framework for .NET and Python only. Every Java team
hand-builds the hosting protocol, tool loop, and memory per project — ~2,500 lines, untested,
copy-pasted.

**What we built** — 11 reusable, tested modules + 21 runnable samples. A Foundry hosted agent
drops from a **1,143-line controller to a handful of config beans**; swapping the model backend is
**one line**; multi-agent orchestration is a **builder call**.

**Proven** — live on Azure Foundry: basic agent, backend swap, function-tool loop, middleware,
and a multi-agent workflow. Full concept parity with the .NET/Python framework; infra recovery
automated in Bicep.

**Impact** — Java teams ship Foundry agents with far less bespoke code; fixes and new features
land **once** in the framework, not re-patched across every agent.
