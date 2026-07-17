---
name: memory
description: 'Save, recall, and search personal memory notes stored as markdown files in the .memory folder. USE FOR: "save memory", "recall memory", "search memory", "remember that", "what do I know about".'
---

# Memory Skill

A personal knowledge store that persists notes as markdown files under `.memory/` in this repository.

## ⚠️ Security Rule — Non-Negotiable

**NEVER save secrets, tokens, passwords, API keys, connection strings, or any sensitive/personal data to memory.**
If a save request contains such data, refuse and explain why. Only save general knowledge, patterns, decisions, and non-sensitive notes.

## Operations

### Save

**Trigger phrases:** "save memory to `<name>`", "remember `<content>` as `<name>`", "update memory `<name>`"

1. Determine the memory name from the user's request (e.g., `pr-reviewed`).
2. Derive the file path: `.memory/<name>.md`.
3. If the file exists, read it first so you can merge/update rather than overwrite.
4. Write (or update) the file with the new content in clean markdown.
5. Include a `Last updated:` timestamp at the bottom of the file.
6. Confirm to the user: "Saved to `.memory/<name>.md`."

**File format:**
```markdown
# <name>

<content>

---
Last updated: YYYY-MM-DD
```

### Recall

**Trigger phrases:** "recall memory `<name>`", "what do I know about `<name>`", "show memory `<name>`"

1. Derive the file path: `.memory/<name>.md`.
2. If the file exists, read and display its content.
3. If the file does not exist, say so and suggest a search instead.

### Search

**Trigger phrases:** "search memory `<query>`", "find memory about `<query>`", "do I have notes on `<query>`"

1. Use grep/glob to search all `.md` files under `.memory/` for the query string (case-insensitive).
2. List matching files with the relevant lines/context.
3. Offer to recall any specific file in full.

## Notes

- Memory files are **local only** — `.memory/` is listed in `.gitignore` and is never committed.
- Keep content concise and factual — memory is a reference, not a journal.
- Use kebab-case for memory names (e.g., `pr-reviewed`, `java-sdk-patterns`).
- If a name is ambiguous, ask the user to clarify before saving.
