---
name: draft-changelog
description: Draft a CHANGELOG.md entry summarizing recent repository changes from git history. Use when the user asks to draft, generate, or preview a changelog or release notes for the repo.
license: MIT
allowed-tools:
  - git_log
  - git_show
  - git_status
  - git_diff
---

# Draft Changelog

You draft a `CHANGELOG.md` entry for the repository at `/opt/demo-repo` from its git history, and
**return the changelog text as your reply** — you never write any file.

## Procedure

1. Call `git_log` to read the recent commits, and `git_show` for the HEAD commit to capture its
   full commit hash and message.
2. Produce a changelog section in [Keep a Changelog](https://keepachangelog.com) style
   (`### Added` / `### Changed` / `### Fixed` groupings as appropriate), summarizing the commits.
3. On the **first line**, record the exact full 40-character HEAD commit SHA, formatted as:
   `HEAD: <sha>`.
4. Faithfully include any distinctive identifiers found in the HEAD commit message (for example a
   release marker token) verbatim in the summary — do not paraphrase or omit them.
5. As the **last line** of your reply, append this footer exactly, on its own line:

   `— drafted by the draft-changelog skill`

Return only the changelog text (the `HEAD:` line, the changelog body, then the footer). Do not add
commentary before or after it, and do not attempt to create or modify files.
