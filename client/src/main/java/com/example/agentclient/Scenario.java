package com.example.agentclient;

import com.openai.client.OpenAIClient;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Registry of selectable end-to-end scenarios. Each pairs a human-friendly <b>slug</b> (used for
 * name-based CLI selection) with the method that runs it. Adding a scenario is a one-line change
 * here — there is no separate dispatch chain or label map to keep in sync.
 */
enum Scenario {
    SMOKE("smoke", AgentTests::runSmokeTest),
    WEB_SEARCH("web-search", AgentTests::runWebSearchTest),
    MEMORY("memory", AgentTests::runMemoryTest),
    CODE_INTERPRETER("code-interpreter", AgentTests::runCodeInterpreterTest),
    MULTI_TURN("multi-turn", AgentTests::runMultiTurnTest),
    STREAMING("streaming", AgentTests::runStreamingTest),
    TODO("todo", AgentTests::runTodoToolTest),
    GIT_MCP("git-mcp", AgentTests::runGitMcpToolTest),
    CHANGELOG_SKILL("changelog-skill", AgentTests::runDraftChangelogSkillTest),
    MIDDLEWARE("middleware", AgentTests::runMiddlewareTest),
    BACKEND_IDENTITY("backend-identity", AgentTests::runBackendIdentityTest);

    /** Runs one end-to-end scenario. Allows checked exceptions (some scenarios block on I/O). */
    @FunctionalInterface
    interface Runner {
        boolean run(OpenAIClient client) throws Exception;
    }

    final String slug;
    final Runner runner;

    Scenario(String slug, Runner runner) {
        this.slug = slug;
        this.runner = runner;
    }

    /** Resolve a CLI token to a scenario by slug (case-insensitive); null if unknown. */
    static Scenario resolve(String token) {
        String t = token.trim();
        for (Scenario s : values()) {
            if (s.slug.equalsIgnoreCase(t)) {
                return s;
            }
        }
        return null;
    }

    /** Comma-separated slug list for help / error messages. */
    static String slugs() {
        return Arrays.stream(values()).map(s -> s.slug).collect(Collectors.joining(", "));
    }
}
