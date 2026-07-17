package com.example.agentclient;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.AzureCliCredentialBuilder;
import com.openai.azure.AzureUrlPathMode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standalone demo client that invokes the remote Foundry <b>hosted agent</b> and verifies its
 * features end-to-end. It builds the OpenAI Responses wire client for the agent endpoint, then runs
 * a selection of scenarios from the {@link Scenario} registry (each implemented in {@link AgentTests}
 * over the {@link Responses} helper layer):
 *
 * <ol>
 *   <li><b>smoke</b> — a plain chat turn asserts the container is alive and the wiring is correct.</li>
 *   <li><b>web-search</b> — asserts a {@code web_search_call} block plus a {@code url_citation}.</li>
 *   <li><b>memory</b> — seeds a throwaway fact, then recalls it in a later turn (server-side memory
 *       store, partitioned by the caller's Microsoft Entra identity).</li>
 *   <li><b>code-interpreter</b> — asks for an exact big-integer computation (20!) and asserts a
 *       {@code code_interpreter_call} block and the correct result.</li>
 *   <li><b>multi-turn</b> — states a transient passphrase, then chains a second turn via
 *       {@code previous_response_id} and asserts the agent recalls it from the thread.</li>
 *   <li><b>streaming</b> — invokes the agent over SSE and asserts text deltas reassemble.</li>
 *   <li><b>todo</b> — writes then reads a task list via the local {@code todo} function tool.</li>
 *   <li><b>git-mcp</b> — inspects a baked repo via the framework local-MCP git client.</li>
 *   <li><b>changelog-skill</b> — exercises the framework {@code AgentSkillsProvider} (progressive
 *       disclosure via {@code load_skill}).</li>
 *   <li><b>backend-identity</b> — asserts the hosted agent stamps {@code metadata.chat_client}.</li>
 * </ol>
 *
 * <h2>Why the OpenAI SDK directly (not {@code ResponsesClient.createAzureResponse})</h2>
 * A hosted agent must be called through its <b>agent endpoint</b>, not the project endpoint with an
 * {@code AgentReference} (that path is only for <em>prompt</em> agents — the service returns
 * <em>"Hosted agents can only be called through the agent endpoint"</em>). The agent endpoint is:
 * <pre>{projectEndpoint}/agents/{agentName}/endpoint/protocols/openai</pre>
 * and it speaks the plain OpenAI Responses protocol, so we point the OpenAI Java client's
 * {@code baseUrl} straight at it. (The SDK's {@code buildOpenAIClient()} can't be reused because it
 * hardcodes {@code endpoint + "/openai/v1"}.)
 *
 * <h2>Auth</h2>
 * Pure Microsoft Entra ID. We use {@link com.azure.identity.AzureCliCredential} directly (rather
 * than {@code DefaultAzureCredential}) so the token always follows the active {@code az login}
 * tenant. {@code DefaultAzureCredential} honours the {@code AZURE_TENANT_ID} env var and forwards it
 * as {@code az account get-access-token --tenant <id>}; if that tenant differs from the signed-in
 * {@code az} session the CLI errors and the whole chain reports "unavailable". A standalone
 * {@code AzureCliCredential} ignores {@code AZURE_TENANT_ID}, avoiding that trap. We request a token
 * for scope {@code https://ai.azure.com/.default} and pass it as the OpenAI bearer credential. The
 * calling identity must hold the <b>Foundry User</b> data-plane role on the account/project — the
 * least-privilege built-in role that grants agent invocation.
 *
 * <p>Run:
 * <pre>
 *   $env:FOUNDRY_PROJECT_ENDPOINT = "https://&lt;account&gt;.services.ai.azure.com/api/projects/&lt;project&gt;"
 *   mvn -q compile exec:java                              # all scenarios
 *   mvn -q compile exec:java "-Dexec.args=smoke web-search"  # by name
 * </pre>
 * Optional env: {@code AGENT_NAME} (default {@code java-hosted-agent}).</p>
 */
public final class AgentDemoClient {

    private static final String ENTRA_SCOPE = "https://ai.azure.com/.default";

    private AgentDemoClient() {
    }

    public static void main(String[] args) throws Exception {
        String endpoint = Env.require("FOUNDRY_PROJECT_ENDPOINT");
        String agentName = Env.env("AGENT_NAME", "java-hosted-agent");
        String apiVersion = Env.env("API_VERSION", "2025-11-15-preview");
        String baseUrl = endpoint.replaceAll("/+$", "")
                + "/agents/" + agentName + "/endpoint/protocols/openai";

        System.out.println("Project endpoint : " + endpoint);
        System.out.println("Agent            : " + agentName);
        System.out.println("Agent base URL   : " + baseUrl);
        System.out.println("api-version      : " + apiVersion);
        System.out.println();

        // Acquire an Entra bearer token for the Foundry data plane. AzureCliCredential (not
        // DefaultAzureCredential) so we don't inherit a stray AZURE_TENANT_ID that mismatches az.
        TokenCredential credential = new AzureCliCredentialBuilder().build();
        AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(ENTRA_SCOPE));

        // The hosted agent endpoint speaks the OpenAI Responses protocol. Two Azure-specific tweaks
        // are required when using the OpenAI *Java* SDK against an *.azure.com host:
        //   1) AzureUrlPathMode.UNIFIED — without it the SDK auto-selects the legacy Azure path
        //      ({base}/openai/deployments/{model}/responses) which the agent endpoint rejects;
        //      UNIFIED posts straight to {base}/responses.
        //   2) api-version query parameter — the Azure data plane requires it (the plain OpenAI
        //      protocol doesn't send one).
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(token.getToken())   // sent as: Authorization: ******
                .azureUrlPathMode(AzureUrlPathMode.UNIFIED)
                .putQueryParam("api-version", apiVersion)
                .build();

        List<Scenario> selected = parseSelection(args);
        List<Scenario> toRun = selected.isEmpty() ? List.of(Scenario.values()) : selected;
        System.out.println("Running tests    : "
                + (selected.isEmpty() ? "ALL (" + Scenario.slugs() + ")"
                        : toRun.stream().map(s -> s.slug).collect(Collectors.joining(", "))));
        System.out.println();

        Map<Scenario, Boolean> results = new LinkedHashMap<>();
        for (Scenario scenario : toRun) {
            results.put(scenario, scenario.runner.run(client));
        }

        System.out.println();
        System.out.println("==================== RESULTS ====================");
        boolean allPass = true;
        for (Map.Entry<Scenario, Boolean> e : results.entrySet()) {
            boolean ok = e.getValue();
            allPass &= ok;
            System.out.printf("  %-18s : %s%n", e.getKey().slug, ok ? "PASS" : "FAIL");
        }
        System.out.println("=================================================");

        if (!allPass) {
            System.exit(1);
        }
    }

    /**
     * Resolves the requested scenarios from CLI args (space- or comma-separated), matching each
     * token by its <b>slug name</b> (e.g. {@code "web-search"}). Order is preserved and duplicates
     * removed. An empty selection means "run all" — honouring the repo's targeted-e2e convention
     * (run the smallest scenario to save tokens and avoid failing on features not deployed yet).
     */
    private static List<Scenario> parseSelection(String[] args) {
        LinkedHashSet<Scenario> sel = new LinkedHashSet<>();
        if (args == null) {
            return new ArrayList<>(sel);
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            for (String tok : arg.split("[,\\s]+")) {
                if (tok.isBlank()) {
                    continue;
                }
                Scenario scenario = Scenario.resolve(tok);
                if (scenario == null) {
                    System.err.println("Ignoring unknown test selector: " + tok);
                    System.err.println("  available: " + Scenario.slugs());
                } else {
                    sel.add(scenario);
                }
            }
        }
        return new ArrayList<>(sel);
    }
}
