package com.example.agentclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.openai.OpenAIResponsesChatClient;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static com.example.agentclient.Env.env;
import static com.example.agentclient.Responses.MODEL;
import static com.example.agentclient.Responses.answerText;
import static com.example.agentclient.Responses.backendLabel;
import static com.example.agentclient.Responses.backendMatches;
import static com.example.agentclient.Responses.hasAnnotationOfType;
import static com.example.agentclient.Responses.hasOutputOfType;
import static com.example.agentclient.Responses.invoke;
import static com.example.agentclient.Responses.metadataValue;
import static com.example.agentclient.Responses.textOf;

/**
 * The end-to-end scenario assertions. Each {@code run*Test} method drives the hosted agent through
 * {@link Responses} and returns whether its feature verified. They are wired into the CLI by the
 * {@link Scenario} registry.
 */
final class AgentTests {

    private static final Pattern SHA_PATTERN = Pattern.compile("\\b[0-9a-f]{40}\\b");

    private AgentTests() {
    }

    // ----- smoke (plain chat, no tools) -----------------------------------------------------

    /**
     * The cheapest possible end-to-end check: a plain chat turn with no tools ("tell me a joke").
     * It just asserts the agent replies with non-empty text — enough to confirm the deployed
     * container is alive, the auth/endpoint wiring is correct, and the model responds. Used to
     * validate a fresh deployment before running the heavier, tool-exercising scenarios.
     */
    static boolean runSmokeTest(OpenAIClient client) {
        System.out.println("---- smoke: plain chat -----------------------------------------");
        String prompt = "Tell me a short, clean one-line joke.";
        JsonNode response = invoke(client, prompt);
        String answer = answerText(response);

        System.out.println("Q: " + prompt);
        System.out.println("A: " + answer);
        System.out.println("  chat backend : " + backendLabel(response));

        boolean pass = !answer.isBlank();
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- backend-identity: chat backend identity ------------------------------------------

    /**
     * Verifies the hosted agent stamps the active chat-client backend onto the response
     * {@code metadata} — asserting {@code metadata.chat_client} is present and, when
     * {@code EXPECTED_CHAT_CLIENT} is set (e.g. {@code langchain4j} or {@code foundry}), matches it.
     * Proves the {@code langchain4j} bridge is distinguishable end-to-end.
     */
    static boolean runBackendIdentityTest(OpenAIClient client) {
        System.out.println();
        System.out.println("---- backend-identity: chat backend identity -------------------");
        String expected = env("EXPECTED_CHAT_CLIENT", "");
        JsonNode response = invoke(client, "Reply with the single word: ok.");

        String backend = metadataValue(response, "chat_client");
        System.out.println("  chat backend : " + backendLabel(response));
        System.out.println("  present      : " + (backend != null && !backend.isBlank()));
        if (!expected.isBlank()) {
            System.out.println("  expected     : " + expected);
        }

        boolean pass = backendMatches(response, expected);
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- web-search -----------------------------------------------------------------------

    static boolean runWebSearchTest(OpenAIClient client) {
        System.out.println("---- web-search ------------------------------------------------");
        String prompt = "Use web search to tell me who the current CEO of Microsoft is, "
                + "and cite the source.";
        JsonNode response = invoke(client, prompt);

        boolean searched = hasOutputOfType(response, "web_search_call");
        boolean cited = hasAnnotationOfType(response, "url_citation");
        String answer = answerText(response);

        System.out.println("Q: " + prompt);
        System.out.println("A: " + answer);
        System.out.println("  web_search_call present : " + searched);
        System.out.println("  url_citation present    : " + cited);

        boolean pass = searched && cited && !answer.isBlank();
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- memory (seed then recall in a separate turn) -------------------------------------

    static boolean runMemoryTest(OpenAIClient client) throws Exception {
        System.out.println();
        System.out.println("---- memory ----------------------------------------------------");
        // A throwaway, unguessable fact so a correct recall cannot be a lucky guess. The codename is
        // unique per run, so an accumulated fact from an earlier run can never satisfy this recall.
        String codename = "Blue-" + UUID.randomUUID().toString().substring(0, 8);

        String seedPrompt = "Please remember this durable fact about me: my secret project "
                + "codename is \"" + codename + "\". Just acknowledge briefly.";
        System.out.println("Seed: " + seedPrompt);
        JsonNode seedResponse = invoke(client, seedPrompt, null);
        System.out.println("A: " + answerText(seedResponse));

        // Memory writes debounce (update_delay ~1s on the backing agent); give it a margin.
        System.out.println("(waiting 5s for the memory write to settle...)");
        Thread.sleep(5000);

        String recallPrompt = "What is my secret project codename? Answer with just the codename.";
        System.out.println("Recall: " + recallPrompt);
        JsonNode recallResponse = invoke(client, recallPrompt, null);
        String answer = answerText(recallResponse);
        System.out.println("A: " + answer);

        boolean searchedMemory = hasOutputOfType(recallResponse, "memory_search_call");
        boolean recalled = answer.toLowerCase(Locale.ROOT).contains(codename.toLowerCase(Locale.ROOT));

        System.out.println("  memory_search_call present  : " + searchedMemory);
        System.out.println("  codename recalled in answer : " + recalled);

        boolean pass = recalled;
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- code-interpreter (model writes + runs Python server-side) ------------------------

    static boolean runCodeInterpreterTest(OpenAIClient client) {
        System.out.println();
        System.out.println("---- code-interpreter ------------------------------------------");
        // A computation that is tedious to "guess" but trivial for Python — exact big integer.
        // 20! = 2432902008176640000
        String expected = "2432902008176640000";
        String prompt = "Use the code interpreter to compute 20 factorial (20!) exactly. "
                + "Reply with just the integer.";
        JsonNode response = invoke(client, prompt);

        boolean ranCode = hasOutputOfType(response, "code_interpreter_call");
        String answer = answerText(response);
        boolean correct = answer.replace(",", "").contains(expected);

        System.out.println("Q: " + prompt);
        System.out.println("A: " + answer);
        System.out.println("  code_interpreter_call present : " + ranCode);
        System.out.println("  exact result in answer        : " + correct);

        boolean pass = correct;
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- multi-turn threading (in-conversation context, not durable memory) ---------------

    static boolean runMultiTurnTest(OpenAIClient client) {
        System.out.println();
        System.out.println("---- multi-turn threading --------------------------------------");
        // Turn 1 states a transient fact; turn 2 (chained via previous_response_id) must recall it
        // from the threaded conversation, NOT the durable memory store. Use an unguessable token.
        String token = "Maple-" + UUID.randomUUID().toString().substring(0, 8);

        String turn1 = "For this conversation only, my session passphrase is \"" + token
                + "\". Acknowledge briefly.";
        System.out.println("Turn 1: " + turn1);
        JsonNode r1 = invoke(client, turn1, null);
        System.out.println("A: " + answerText(r1));
        String previousId = textOf(r1, "id");
        System.out.println("  previous response id : " + previousId);

        String turn2 = "What is my session passphrase? Reply with just the passphrase.";
        System.out.println("Turn 2: " + turn2);
        JsonNode r2 = invoke(client, turn2, previousId);
        String answer = answerText(r2);
        System.out.println("A: " + answer);

        boolean recalled = answer.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
        System.out.println("  passphrase carried across turns : " + recalled);

        boolean pass = recalled && previousId != null && !previousId.isBlank();
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- streaming (SSE Responses event stream) -------------------------------------------

    static boolean runStreamingTest(OpenAIClient client) {
        System.out.println();
        System.out.println("---- streaming (SSE) -------------------------------------------");
        String prompt = "In one sentence, what is the Azure SDK for Java?";
        System.out.println("Q: " + prompt);

        StringBuilder assistant = new StringBuilder();
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger deltas = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completion = new CountDownLatch(1);
        ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();

        try {
            OpenAIResponsesChatClient frameworkClient =
                    new OpenAIResponsesChatClient(client, streamingExecutor);
            frameworkClient.getStreamingResponse(
                            java.util.List.of(ChatMessage.user(prompt)),
                            ChatOptions.builder().modelId(MODEL).build())
                    .subscribe(new Flow.Subscriber<ChatResponseUpdate>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(ChatResponseUpdate update) {
                            updates.incrementAndGet();
                            if (!update.getText().isEmpty()) {
                                assistant.append(update.getText());
                                deltas.incrementAndGet();
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            failure.set(throwable);
                            completion.countDown();
                        }

                        @Override
                        public void onComplete() {
                            completion.countDown();
                        }
                    });
            if (!completion.await(2, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Streaming response timed out");
            }
            if (failure.get() != null) {
                throw new RuntimeException("Streaming response failed", failure.get());
            }
        } catch (Exception e) {
            System.out.println("  streaming failed: " + e);
            System.out.println("  => FAIL");
            return false;
        } finally {
            streamingExecutor.shutdownNow();
        }

        String answer = assistant.toString().trim();
        System.out.println("A: " + answer);
        System.out.println("  framework updates       : " + updates.get());
        System.out.println("  text update count       : " + deltas.get());
        System.out.println("  stream completed        : true");

        boolean pass = updates.get() > 0 && deltas.get() > 0 && !answer.isBlank();
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- todo tool (local function tool executed in-container) ----------------------------

    static boolean runTodoToolTest(OpenAIClient client) {
        System.out.println();
        System.out.println("---- todo tool -------------------------------------------------");
        // The `todo` tool is a LOCAL function tool: the model emits a function_call, the container
        // executes it and threads the result back (the function_call plumbing is not surfaced to the
        // client). TodoService state is IN-MEMORY, per session-sandbox — and NEITHER
        // previous_response_id NOR conversation reliably pins two separate HTTP requests to the same
        // container instance (conversation threading is stored in the model backend, not the sandbox;
        // the platform is free to route the second request to a cold replica whose map is empty). So
        // we WRITE and READ in a SINGLE request: the container's tool loop runs both function calls on
        // one instance, exercising the full write→read round-trip deterministically.
        String taskA = "Alpha-" + UUID.randomUUID().toString().substring(0, 8);
        String taskB = "Beta-" + UUID.randomUUID().toString().substring(0, 8);
        String taskC = "Gamma-" + UUID.randomUUID().toString().substring(0, 8);
        System.out.println("  tasks=[" + taskA + ", " + taskB + ", " + taskC + "]");

        String prompt = "Do BOTH of the following using your todo tool, calling it each time:\n"
                + "(1) create a task list with exactly these three tasks, in order: \""
                + taskA + "\", \"" + taskB + "\", \"" + taskC + "\" "
                + "(set the first to in_progress and the others to pending);\n"
                + "(2) then read the current task list back.\n"
                + "Finally, reply with just each task's content, one per line.";
        System.out.println("Prompt: " + prompt);
        JsonNode response = invoke(client, prompt, null);
        String answer = answerText(response);
        System.out.println("A: " + answer);

        String lower = answer.toLowerCase(Locale.ROOT);
        boolean hasA = lower.contains(taskA.toLowerCase(Locale.ROOT));
        boolean hasB = lower.contains(taskB.toLowerCase(Locale.ROOT));
        boolean hasC = lower.contains(taskC.toLowerCase(Locale.ROOT));
        System.out.println("  task A round-trips : " + hasA);
        System.out.println("  task B round-trips : " + hasB);
        System.out.println("  task C round-trips : " + hasC);

        boolean pass = hasA && hasB && hasC;
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- git-mcp tool (local MCP server invoked via the framework MCP client) --------------

    static boolean runGitMcpToolTest(OpenAIClient client) {
        System.out.println();
        System.out.println("---- git-mcp tool ----------------------------------------------");
        // The agent is wired (MCP_ENABLED=true) with a read-only local MCP server (mcp-server-git)
        // over stdio, exposed by the framework's McpToolSource as function tools. The container bakes
        // a synthetic repo at /opt/demo-repo whose HEAD commit message carries a unique marker
        // (ZTOKEN-9f3a1c) that does NOT exist on the public internet — so web search cannot shortcut
        // it and the model cannot guess it. A correct answer PROVES the git MCP tool actually ran.
        String marker = "ZTOKEN-9f3a1c";
        String prompt = "Use your git tools to inspect the repository at /opt/demo-repo. "
                + "Report the commit message of HEAD (the most recent commit) verbatim. "
                + "Reply with just that commit message.";
        System.out.println("Prompt: " + prompt);
        JsonNode response = invoke(client, prompt);
        String answer = answerText(response);
        System.out.println("A: " + answer);

        boolean hasMarker = answer.contains(marker);
        boolean mentionsGizmo = answer.toLowerCase(Locale.ROOT).contains("gizmo");
        System.out.println("  unique marker present : " + hasMarker + " (" + marker + ")");
        System.out.println("  mentions 'gizmo'      : " + mentionsGizmo);

        boolean pass = hasMarker;
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    // ----- draft-changelog Agent Skill (progressive disclosure) -----------------------------

    static boolean runDraftChangelogSkillTest(OpenAIClient client) {
        System.out.println();
        System.out.println("---- changelog-skill -------------------------------------------");
        // The agent is wired (SKILLS_ENABLED=true) with the framework AgentSkillsProvider over a
        // baked skill (/opt/skills/draft-changelog). The L1 index only advertises the skill's name +
        // generic description; the L2 body (fetched via load_skill) carries the exact procedure:
        // read git history, then RETURN a CHANGELOG with the HEAD SHA on line 1 and a distinctive
        // footer. Three anchored facts prove the full path ran end-to-end:
        //   * a 40-hex SHA           -> the skill read the repo (deterministic, dates pinned in image);
        //   * the ZTOKEN-9f3a1c marker -> git was actually inspected, not guessed;
        //   * the footer text         -> load_skill/progressive disclosure ran (it exists ONLY in the
        //                                L2 body, not in the advertised L1 description).
        String marker = "ZTOKEN-9f3a1c";
        String footer = "drafted by the draft-changelog skill";
        String prompt = "Draft a changelog for the repository at /opt/demo-repo. "
                + "Follow the draft-changelog skill exactly.";
        System.out.println("Prompt: " + prompt);
        JsonNode response = invoke(client, prompt);
        String answer = answerText(response);
        System.out.println("A: " + answer);

        boolean hasSha = SHA_PATTERN.matcher(answer.toLowerCase(Locale.ROOT)).find();
        boolean hasMarker = answer.contains(marker);
        boolean hasFooter = answer.toLowerCase(Locale.ROOT).contains(footer);
        System.out.println("  40-hex HEAD SHA present : " + hasSha);
        System.out.println("  unique marker present   : " + hasMarker + " (" + marker + ")");
        System.out.println("  skill footer present    : " + hasFooter);

        boolean pass = hasSha && hasMarker && hasFooter;
        System.out.println("  => " + (pass ? "PASS" : "FAIL"));
        return pass;
    }
}
