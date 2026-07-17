package com.example.hostedagent;

/**
 * A single task in the session TODO list.
 *
 * <p>Shape mirrors the built-in task-list tools of real coding harnesses (opencode's
 * {@code todowrite}, Claude Code's {@code TodoWrite}), with a stable {@code id} added so the list
 * is deterministically addressable and testable. {@code priority} is optional and may be
 * {@code null}.</p>
 *
 * @param id       stable unique identifier the model reuses across writes to track the same task
 * @param content  brief, actionable task description
 * @param status   one of {@code pending}, {@code in_progress}, {@code completed}, {@code cancelled}
 * @param priority optional {@code high}/{@code medium}/{@code low}, or {@code null}
 */
public record TodoItem(String id, String content, String status, String priority) {
}
