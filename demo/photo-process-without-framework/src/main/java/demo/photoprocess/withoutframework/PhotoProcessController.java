package demo.photoprocess.withoutframework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.azure.ai.agents.AgentsClientBuilder;
import com.azure.ai.agents.MemoryStoresClient;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Hand-written Foundry hosted-agent host — the "without framework" baseline. This is the
 * counterpart to what the framework project inherits as beans in {@code PhotoProcessConfiguration}
 * ({@code HealthController} + {@code ResponsesEndpoint} + {@code AgentResponseHandler}). There is no
 * agent library here: this controller wires the app itself and hand-implements the container wire
 * contract (see the lessons-learned gist, summarised in {@code demo/NOTES.md}):
 * <ul>
 *   <li>the {@code GET /readiness} (+ {@code /healthz}, {@code /}) probe on port 8088 — the platform
 *       health-probes this before routing;</li>
 *   <li>routing {@code POST /responses} to buffered JSON or streamed SSE;</li>
 *   <li>parsing the request and shaping the response object (delegated to {@link ResponsesJson});</li>
 *   <li>emitting correctly-<em>named</em> SSE events ({@code event: response.completed}) terminated
 *       without a {@code [DONE]} sentinel — the two mistakes that cause {@code streaming_incomplete}.</li>
 * </ul>
 *
 * <p>The routing itself mirrors the framework's {@code PhotoProcessMiddleware}: <b>photo attached</b>
 * runs the crop workflow (an atomic artifact, emitted as one SSE delta); <b>no photo</b> is a normal
 * chat turn against {@link #DEFAULT_INSTRUCTIONS} — and there the streaming path is a real, verbatim
 * relay of the model's own SSE frames (what the framework's ChatClient does internally). The app
 * logic lives in {@link PhotoProcessWorkflow}; everything in this file plus {@link ModelClient} and
 * {@link ResponsesJson} is what the framework removes.</p>
 */
@RestController
public class PhotoProcessController {

    private static final Logger LOG = LoggerFactory.getLogger(PhotoProcessController.class);

    /** Same persona as the framework project's {@code PhotoProcessConfiguration.DEFAULT_INSTRUCTIONS}. */
    private static final String DEFAULT_INSTRUCTIONS =
            "You are a helpful photography assistant. Give clear, practical advice on composition, "
                    + "lighting, gear, and editing. When the user attaches a photo it is automatically "
                    + "cropped for maximum impact, so focus your replies on the photography question.";

    // A trusted caller asserts the per-user memory partition via a marker line in the input (mirrors
    // the Foundry reference); matched per-line, case-insensitive, and stripped before the model sees it.
    private static final Pattern SCOPE_MARKER =
            Pattern.compile("(?im)^\\s*\\[memory_scope\\]\\s*value=(.+?)\\s*$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelClient model;
    private final PhotoProcessWorkflow workflow;
    private final MemoryService memory;   // null when no FOUNDRY_PROJECT_ENDPOINT is configured
    private final String modelId;
    private final String instructions;
    private final boolean allowScopeOverride;

    public PhotoProcessController(
            @Value("${MODEL:gpt-4o-mini}") String model,
            @Value("${OPENAI_BASE_URL:https://api.openai.com/v1}") String baseUrl,
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${AGENT_INSTRUCTIONS:}") String instructions,
            @Value("${FOUNDRY_PROJECT_ENDPOINT:}") String projectEndpoint,
            @Value("${MEMORY_STORE_NAME:memstore-photo}") String storeName,
            @Value("${MEMORY_SCOPE:demo-user}") String defaultScope,
            @Value("${ALLOW_MEMORY_SCOPE_OVERRIDE:true}") boolean allowScopeOverride) {
        this.modelId = model;
        this.instructions = (instructions == null || instructions.isBlank())
                ? DEFAULT_INSTRUCTIONS : instructions;
        this.allowScopeOverride = allowScopeOverride;
        // Wire the app by hand: transport + workflow (the framework's Configuration does this for you).
        this.model = new ModelClient(mapper, model, baseUrl, apiKey);
        this.workflow = new PhotoProcessWorkflow(this.model, mapper);
        // Durable memory: build the Foundry memory SDK client and the hand-written orchestration.
        // (The framework project needs none of this — it attaches a FoundryMemoryProvider in one line.)
        if (projectEndpoint != null && !projectEndpoint.isBlank()) {
            MemoryStoresClient stores = new AgentsClientBuilder()
                    .endpoint(projectEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildMemoryStoresClient();
            this.memory = new MemoryService(stores, storeName, defaultScope,
                    1, 5, 2000, "", true, 3, 4000);
        } else {
            this.memory = null;
        }
    }

    /** Liveness/readiness probe. The Foundry platform polls {@code /readiness} before routing. */
    @GetMapping({"/", "/healthz", "/readiness"})
    public String health() {
        return "ok";
    }

    /**
     * Single Responses entry point. Routes like the framework middleware: a photo runs the crop
     * workflow (atomic artifact); no photo is a chat turn. Streams SSE when the caller asks for it
     * (body {@code stream:true} or {@code Accept: text/event-stream}); otherwise returns one buffered
     * JSON Response.
     */
    @PostMapping("/responses")
    public void createResponse(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Accept", required = false) String accept,
            @RequestHeader(value = "x-agent-user-isolation-key", required = false) String userIsolationKey,
            HttpServletResponse httpResponse)
            throws Exception {
        JsonNode input = mapper.valueToTree(body.get("input"));
        String rawText = ResponsesJson.extractText(input);
        Attachment photo = ResponsesJson.extractImage(input);
        boolean stream = asBoolean(body.get("stream"))
                || (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE));

        if (photo != null) {
            // Photo attached → crop mode. The cropped image is a single atomic artifact, so even the
            // streamed form is one delta (there is nothing to relay token-by-token). Memory does not
            // apply here — like the framework side, the crop path short-circuits before the model turn.
            LOG.info("POST /responses ({}) model={} mode=crop", stream ? "sse" : "json", modelId);
            String assistantText = workflow.run(rawText, photo);
            if (stream) {
                writeSse(httpResponse, assistantText);
            } else {
                writeJson(httpResponse, assistantText);
            }
            return;
        }

        // No photo → normal chat turn against the photography-assistant persona, WITH durable memory:
        // resolve the per-user scope, recall facts into the instructions, and remember the exchange.
        String scope = resolveScope(userIsolationKey, rawText);
        String userText = stripScopeMarker(rawText);
        String fullInstructions = computeInstructions(userText, scope);
        LOG.info("POST /responses ({}) model={} mode=chat memory={}",
                stream ? "sse" : "json", modelId, memory != null);

        if (stream) {
            relayChat(httpResponse, fullInstructions, userText, scope);
        } else {
            String assistantText = model.completeText(fullInstructions, userText);
            if (memory != null) {
                memory.remember(userText, assistantText, scope);
            }
            writeJson(httpResponse, assistantText);
        }
    }

    // ---- Durable memory orchestration (the framework's FoundryMemoryProvider does all this) ------

    /**
     * Resolves the memory partition for this turn: a trusted-caller {@code [memory_scope] value=…}
     * marker in the input (when overrides are allowed) wins, else the platform-injected
     * {@code x-agent-user-isolation-key} header, else {@code null} (MemoryService falls back to its
     * env default). The framework project never writes this — the provider owns scope resolution.
     */
    private String resolveScope(String userIsolationKey, String rawInput) {
        if (allowScopeOverride && rawInput != null) {
            Matcher m = SCOPE_MARKER.matcher(rawInput);
            if (m.find() && !m.group(1).trim().isBlank()) {
                return m.group(1).trim();
            }
        }
        return (userIsolationKey != null && !userIsolationKey.isBlank()) ? userIsolationKey.trim() : null;
    }

    /** Removes any {@code [memory_scope] value=…} marker so it never reaches the model as content. */
    private static String stripScopeMarker(String rawInput) {
        return (rawInput == null || rawInput.isEmpty())
                ? rawInput : SCOPE_MARKER.matcher(rawInput).replaceAll("").strip();
    }

    /** Builds the recalled-memory-augmented system instructions once for a user turn. */
    private String computeInstructions(String userText, String scope) {
        String recalled = memory == null ? "" : memory.recall(userText, scope);
        return recalled.isBlank()
                ? instructions
                : instructions + "\n\nRelevant memories about the user:\n" + recalled;
    }

    // ---- Chat streaming: a REAL verbatim relay of the model's own SSE frames --------------------

    /**
     * Opens a streaming Responses call to the model and forwards its SSE frames verbatim. The model's
     * frames are already NAMED and terminate on {@code response.completed} (no {@code [DONE]}), so a
     * straight pass-through is a fully-compliant hosted-agent stream. This is exactly what the
     * framework's ChatClient does under the hood — here we do it by hand.
     *
     * <p>The upstream call is opened <em>before</em> any SSE headers are written, so a non-2xx model
     * response throws and is reported as a normal JSON error rather than a half-written stream.</p>
     *
     * <p>Memory adds a wrinkle the buffered path doesn't have: to {@code remember} the exchange we
     * must also reassemble the assistant's text from the very frames we are relaying — so we tap each
     * {@code output_text.delta} on the way through. The framework's provider gets the full response for
     * free; here we hand-roll the capture.</p>
     */
    private void relayChat(HttpServletResponse http, String fullInstructions, String userText,
            String scope) throws Exception {
        Stream<String> upstream = model.openChatStream(fullInstructions, userText);

        http.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        http.setCharacterEncoding(StandardCharsets.UTF_8.name());
        http.setHeader("Cache-Control", "no-cache");
        http.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = http.getWriter();
        StringBuilder assistant = new StringBuilder();

        try (upstream) {
            upstream.forEach(line -> {
                // Defensive: some gateways still append a [DONE] sentinel the Responses contract forbids.
                if ("data: [DONE]".equals(line.trim())) {
                    return;
                }
                captureDelta(line, assistant); // tap the assistant text so we can remember it later
                writer.write(line);
                writer.write("\n");
                if (line.isEmpty()) {
                    writer.flush(); // blank line terminates an SSE frame — flush it out immediately.
                }
            });
            writer.flush();
        }
        if (memory != null) {
            memory.remember(userText, assistant.toString(), scope);
        }
    }

    /** Reassembles the assistant text from a relayed {@code response.output_text.delta} data line. */
    private void captureDelta(String line, StringBuilder assistant) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(line.substring("data:".length()).trim());
            if ("response.output_text.delta".equals(node.path("type").asText())) {
                assistant.append(node.path("delta").asText(""));
            }
        } catch (Exception ignore) {
            // non-JSON keep-alive/comment line — nothing to capture.
        }
    }

    // ---- Response serialisation: buffered JSON and streamed, NAMED SSE frames -------------------

    private void writeJson(HttpServletResponse http, String assistantText) throws Exception {
        http.setContentType(MediaType.APPLICATION_JSON_VALUE);
        http.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try (PrintWriter writer = http.getWriter()) {
            writer.write(mapper.writeValueAsString(
                    ResponsesJson.responseObject(mapper, modelId, assistantText, "completed")));
        }
    }

    /**
     * Emits the Responses SSE lifecycle by hand for the crop artifact. Every frame is NAMED
     * ({@code event: <type>}) and the stream ends with {@code response.completed} — NOT a
     * {@code data: [DONE]} sentinel (which the Responses gateway rejects). Getting either of these
     * wrong yields {@code streaming_incomplete}.
     */
    private void writeSse(HttpServletResponse http, String assistantText) throws Exception {
        http.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        http.setCharacterEncoding(StandardCharsets.UTF_8.name());
        http.setHeader("Cache-Control", "no-cache");
        http.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = http.getWriter();

        ObjectNode created = mapper.createObjectNode();
        created.put("type", "response.created");
        created.set("response", ResponsesJson.responseObject(mapper, modelId, "", "in_progress"));
        writeFrame(writer, "response.created", created);

        ObjectNode delta = mapper.createObjectNode();
        delta.put("type", "response.output_text.delta");
        delta.put("item_id", "msg_out");
        delta.put("output_index", 0);
        delta.put("content_index", 0);
        delta.put("delta", assistantText);
        writeFrame(writer, "response.output_text.delta", delta);

        ObjectNode completed = mapper.createObjectNode();
        completed.put("type", "response.completed");
        completed.set("response", ResponsesJson.responseObject(mapper, modelId, assistantText, "completed"));
        writeFrame(writer, "response.completed", completed);
        // Intentionally NO "data: [DONE]" line — the Responses contract ends on response.completed.
    }

    private void writeFrame(PrintWriter writer, String eventName, JsonNode payload)
            throws Exception {
        writer.write("event: " + eventName + "\n");
        writer.write("data: " + mapper.writeValueAsString(payload) + "\n\n");
        writer.flush();
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }
}
