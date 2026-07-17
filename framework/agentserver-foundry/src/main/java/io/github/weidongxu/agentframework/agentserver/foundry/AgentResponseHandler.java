package io.github.weidongxu.agentframework.agentserver.foundry;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agentserver.responses.ConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.InMemoryConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.PlatformContext;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseContext;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseHandler;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseRequest;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseSink;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseStore;
import io.github.weidongxu.agentframework.agentserver.responses.StoredResponse;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import io.github.weidongxu.agentframework.chat.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Foundry {@link ResponseHandler} — the Java counterpart of
 * {@code Microsoft.Agents.AI.Foundry.Hosting}. Bridges an {@link Agent} to the AgentServer
 * {@code /responses} protocol: it parses the OpenAI Responses request body, threads conversation
 * history via a host-owned {@link ConversationStore} (never forwarding the caller's ids upstream),
 * runs the agent, and shapes the buffered or streaming Responses envelope through the
 * transport-neutral {@link ResponseSink}.
 *
 * <p>This class is web-framework-free; a host binding (e.g. {@code agentserver-spring}) adapts native
 * HTTP to the SPI and supplies the sink.</p>
 */
public final class AgentResponseHandler implements ResponseHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AgentResponseHandler.class);

    private final Agent agent;
    private final ObjectMapper objectMapper;
    private final Duration streamingTimeout;
    private final ConversationStore conversationStore;
    private final boolean strictSessionLookup;
    private final ResponseStore responseStore;
    private Map<String, Object> responseMetadata = Collections.emptyMap();

    public AgentResponseHandler(Agent agent, ObjectMapper objectMapper) {
        this(agent, objectMapper, Duration.ofMinutes(5));
    }

    public AgentResponseHandler(
            Agent agent,
            ObjectMapper objectMapper,
            Duration streamingTimeout) {
        this(agent, objectMapper, streamingTimeout, new InMemoryConversationStore(), false);
    }

    /**
     * @param conversationStore   host-owned history store; {@code null} disables server-side
     *                            threading (the caller's ids are then ignored, not forwarded)
     * @param strictSessionLookup when {@code true}, an unknown session key yields HTTP 404 (MAF
     *                            parity); when {@code false}, unknown keys start a fresh thread
     *                            (needed for gateway-supplied {@code conv_*} ids the host has never
     *                            seen, e.g. the Foundry Playground)
     */
    public AgentResponseHandler(
            Agent agent,
            ObjectMapper objectMapper,
            Duration streamingTimeout,
            ConversationStore conversationStore,
            boolean strictSessionLookup) {
        this(agent, objectMapper, streamingTimeout, conversationStore, strictSessionLookup, null);
    }

    /**
     * @param responseStore optional store for issued responses, backing the response-lifecycle
     *                      routes ({@code GET/DELETE /responses/{id}}, {@code /cancel},
     *                      {@code /input_items}); {@code null} disables response persistence
     */
    public AgentResponseHandler(
            Agent agent,
            ObjectMapper objectMapper,
            Duration streamingTimeout,
            ConversationStore conversationStore,
            boolean strictSessionLookup,
            ResponseStore responseStore) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.streamingTimeout = Objects.requireNonNull(streamingTimeout, "streamingTimeout");
        if (streamingTimeout.isZero() || streamingTimeout.isNegative()) {
            throw new IllegalArgumentException("streamingTimeout must be positive");
        }
        this.conversationStore = conversationStore;
        this.strictSessionLookup = strictSessionLookup;
        this.responseStore = responseStore;
    }

    /**
     * Stamps the given metadata onto every framework-built response envelope's {@code metadata} map
     * (server-side response tagging, mirroring the Responses {@code metadata} field). Applies to the
     * buffered body, the streamed {@code response.*} events, and the persisted lifecycle record. A
     * {@code null} or empty map clears it. Returns {@code this} for fluent wiring.
     */
    public AgentResponseHandler withResponseMetadata(Map<String, Object> metadata) {
        this.responseMetadata = (metadata == null || metadata.isEmpty())
                ? Collections.emptyMap()
                : new LinkedHashMap<>(metadata);
        return this;
    }

    @Override
    public void handle(ResponseRequest request, ResponseContext context, ResponseSink sink)
            throws Exception {
        Map<String, Object> body = request.body();
        PlatformContext platform = context.platformContext();
        // Foundry Responses protocol 2.0.0 sends x-agent-user-id (+ x-agent-foundry-call-id);
        // protocol 1.0.0 sends x-agent-user-isolation-key. PlatformContext already resolves whichever
        // identifies the end user; use it as the session scope so per-user memory/tools partition.
        String userScope = platform.userId();
        String foundryCallId = platform.callId();
        List<ChatMessage> inputMessages = requestMessages(body.get("input"));
        AgentRunOptions options = runOptions(body, userScope, foundryCallId);
        // Stash the platform call id on the session so context providers (e.g. FoundryMemoryProvider)
        // can forward x-agent-foundry-call-id on their outbound Foundry (Storage) calls.
        Map<String, Object> sessionState = foundryCallId == null || foundryCallId.isEmpty()
                ? Collections.emptyMap()
                : Collections.singletonMap("x-agent-foundry-call-id", foundryCallId);
        AgentSession session = userScope == null
                ? null
                : new AgentSession(userScope, sessionState);

        // Server-side threading (MAF parity): the caller's conversation / previous_response_id id is
        // a key into our OWN history store — never forwarded upstream. Load prior turns, prepend them
        // to this turn's input, and let the model see the full reconstructed message list.
        String conversationId = idValue(body.get("conversation"));
        String previousResponseId = stringValue(body.get("previous_response_id")).orElse(null);
        // previous_response_id takes priority over conversation as the lookup key (MAF).
        String sessionKey = firstNonBlank(previousResponseId, conversationId);
        List<ChatMessage> messages = new ArrayList<>();
        if (conversationStore != null && sessionKey != null) {
            List<ChatMessage> history = conversationStore.load(sessionKey);
            LOG.info("threading: sessionKey={} prevRespId={} convId={} loadedHistory={}",
                    sessionKey, previousResponseId, conversationId,
                    history == null ? -1 : history.size());
            if (history == null && strictSessionLookup) {
                notFound(sink, sessionKey);
                return;
            }
            if (history != null) {
                messages.addAll(history);
            }
        } else {
            LOG.info("threading: no lookup (storePresent={} sessionKey={})",
                    conversationStore != null, sessionKey);
        }
        messages.addAll(inputMessages);

        List<Map<String, Object>> inputItems = normalizeInputItems(body.get("input"));
        boolean background = booleanValue(body.get("background"));
        if (context.streamRequested()) {
            stream(messages, session, options, sink, conversationId, inputItems, background);
        } else {
            buffered(messages, session, options, sink, conversationId, inputItems, background);
        }
    }

    private void notFound(ResponseSink sink, String sessionKey) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", "Conversation or response with id '" + sessionKey + "' not found.");
        error.put("type", "invalid_request_error");
        error.put("code", "not_found");
        sink.writeError(404, objectMapper.writeValueAsString(
                Collections.singletonMap("error", error)));
    }

    /**
     * Persists a completed turn under both the newly issued response id and, when present, the
     * conversation id — so the next turn's {@code previous_response_id} or {@code conversation}
     * resolves to this full history.
     */
    private void persistTurn(
            String responseId,
            String conversationId,
            List<ChatMessage> promptMessages,
            List<ChatMessage> outputMessages) {
        if (conversationStore == null) {
            return;
        }
        List<ChatMessage> updated = new ArrayList<>(promptMessages);
        if (outputMessages != null) {
            updated.addAll(outputMessages);
        }
        if (responseId != null && !responseId.isEmpty()) {
            conversationStore.save(responseId, updated);
        }
        if (conversationId != null) {
            conversationStore.save(conversationId, updated);
        }
        LOG.info("persist: respId={} convId={} savedMessages={}",
                responseId, conversationId, updated.size());
    }

    private void buffered(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options,
            ResponseSink sink,
            String conversationId,
            List<Map<String, Object>> inputItems,
            boolean background) throws Exception {
        AgentResponse response = agent.run(messages, session, options)
                .toCompletableFuture()
                .get();
        Object body = bufferedBody(response, options);
        String issuedResponseId = extractResponseId(body);
        List<ChatMessage> assistantMessages = assistantMessages(response);
        persistTurn(issuedResponseId, conversationId, messages, assistantMessages);
        if (conversationId != null) {
            body = withConversation(body, conversationId);
        }
        if (body instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) body;
            applyMetadata(envelope, responseMetadata);
        }
        persistResponse(issuedResponseId, body, inputItems, background);
        sink.writeJson(objectMapper.writeValueAsString(body));
    }

    private void stream(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options,
            ResponseSink sink,
            String conversationId,
            List<Map<String, Object>> inputItems,
            boolean background) throws Exception {
        PrintWriter writer = sink.beginSse();
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamingResponseWriter subscriber =
                new StreamingResponseWriter(
                        writer, objectMapper, options, completion, failure, responseMetadata);
        agent.runStreaming(messages, session, options).subscribe(subscriber);

        // The SSE response body is already committed once streaming starts, so we must NOT rethrow:
        // the host can no longer produce a clean JSON error onto the committed text/event-stream.
        // Instead, ensure a terminal response.failed event is emitted (the subscriber already does
        // this on error) and then just log and return.
        if (!completion.await(streamingTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            // Timeout: the subscriber has not emitted a terminal event yet — force one, then stop.
            subscriber.onError(new IllegalStateException("Agent response stream timed out"));
            subscriber.cancel();
            LOG.warn("Agent response stream timed out after {} ms; emitted response.failed",
                    streamingTimeout.toMillis());
            return;
        }
        if (failure.get() != null) {
            // Failure: the subscriber already emitted response.failed before counting down.
            LOG.warn("Agent response stream failed; response.failed already emitted",
                    failure.get());
            return;
        }
        // Persist the completed turn so the next turn can thread from it.
        String assistantText = subscriber.finalText();
        List<ChatMessage> assistantMessages = assistantText.isEmpty()
                ? Collections.emptyList()
                : Collections.singletonList(ChatMessage.assistant(assistantText));
        persistTurn(subscriber.responseId(), conversationId, messages, assistantMessages);
        persistStreamedResponse(subscriber.responseId(), options, assistantMessages,
                conversationId, inputItems, background);
    }

    /** Persists the buffered response envelope so the lifecycle routes can serve it. */
    private void persistResponse(
            String responseId,
            Object body,
            List<Map<String, Object>> inputItems,
            boolean background) {
        if (responseStore == null || responseId == null || responseId.isEmpty()) {
            return;
        }
        Map<String, Object> envelope = toEnvelopeMap(body);
        String status = String.valueOf(envelope.getOrDefault("status", "completed"));
        responseStore.save(new StoredResponse(
                responseId, createdAtOf(envelope), status, background, envelope, inputItems));
    }

    /** Reconstructs and persists the streamed response envelope for the lifecycle routes. */
    private void persistStreamedResponse(
            String responseId,
            AgentRunOptions options,
            List<ChatMessage> assistantMessages,
            String conversationId,
            List<Map<String, Object>> inputItems,
            boolean background) {
        if (responseStore == null || responseId == null || responseId.isEmpty()) {
            return;
        }
        List<Map<String, Object>> output = new ArrayList<>();
        assistantMessages.forEach(message -> addMessageOutput(output, message));
        Map<String, Object> envelope = responseEnvelope(
                responseId, model(options), output, "completed");
        if (conversationId != null) {
            envelope.put("conversation", Collections.singletonMap("id", conversationId));
        }
        applyMetadata(envelope, responseMetadata);
        responseStore.save(new StoredResponse(
                responseId, createdAtOf(envelope), "completed", background, envelope, inputItems));
    }

    private Map<String, Object> toEnvelopeMap(Object body) {
        if (body instanceof Map<?, ?>) {
            Map<String, Object> map = new LinkedHashMap<>();
            ((Map<?, ?>) body).forEach((key, value) -> map.put(String.valueOf(key), value));
            return map;
        }
        return objectMapper.convertValue(body, new com.fasterxml.jackson.core.type.TypeReference<
                LinkedHashMap<String, Object>>() { });
    }

    private static long createdAtOf(Map<String, Object> envelope) {
        Object createdAt = envelope.get("created_at");
        if (createdAt instanceof Number) {
            return ((Number) createdAt).longValue();
        }
        return Instant.now().getEpochSecond();
    }

    /**
     * Normalizes a Responses {@code input} value into stored input items for
     * {@code GET /responses/{id}/input_items}. String inputs become a single user message item;
     * list inputs are copied verbatim (each ensured to carry an {@code id}).
     */
    private static List<Map<String, Object>> normalizeInputItems(Object input) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (input instanceof Iterable<?>) {
            for (Object element : (Iterable<?>) input) {
                if (element instanceof Map<?, ?>) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    ((Map<?, ?>) element).forEach((key, value) -> item.put(String.valueOf(key), value));
                    item.putIfAbsent("id", generatedId("msg"));
                    items.add(item);
                } else {
                    items.add(userMessageItem(String.valueOf(element)));
                }
            }
        } else if (input != null) {
            items.add(userMessageItem(input.toString()));
        }
        return items;
    }

    private static Map<String, Object> userMessageItem(String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", generatedId("msg"));
        item.put("type", "message");
        item.put("role", "user");
        item.put("content", Collections.singletonList(Map.of("type", "input_text", "text", text)));
        return item;
    }

    private static List<ChatMessage> assistantMessages(AgentResponse response) {
        List<ChatMessage> assistant = new ArrayList<>();
        for (ChatMessage message : response.getMessages()) {
            if (message.getRole() == ChatRole.ASSISTANT) {
                assistant.add(message);
            }
        }
        return assistant;
    }

    private String extractResponseId(Object body) {
        if (body instanceof Map<?, ?>) {
            Object id = ((Map<?, ?>) body).get("id");
            return id == null ? null : id.toString();
        }
        try {
            JsonNode node = objectMapper.valueToTree(body);
            String id = node.path("id").asText(null);
            return id == null || id.isEmpty() ? null : id;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /** Echoes {@code conversation: {id}} into the response body (MAF parity for conv_* callers). */
    private Map<String, Object> withConversation(Object body, String conversationId) {
        Map<String, Object> map;
        if (body instanceof Map<?, ?>) {
            map = new LinkedHashMap<>();
            ((Map<?, ?>) body).forEach((key, value) -> map.put(String.valueOf(key), value));
        } else {
            map = objectMapper.convertValue(body, new com.fasterxml.jackson.core.type.TypeReference<
                    LinkedHashMap<String, Object>>() { });
        }
        map.put("conversation", Collections.singletonMap("id", conversationId));
        return map;
    }

    private Object bufferedBody(AgentResponse response, AgentRunOptions options) {
        Object raw = response.getRawRepresentation();
        if (isResponseObject(raw) && !hasApprovalRequest(response)) {
            return raw;
        }

        String responseId = valueOr(response.getResponseId(), generatedId("resp"));
        List<Map<String, Object>> output = new ArrayList<>();
        response.getMessages().forEach(message -> {
            if (message.getRole() == ChatRole.ASSISTANT) {
                addMessageOutput(output, message);
            }
        });
        Map<String, Object> body = responseEnvelope(
                responseId,
                model(options),
                output,
                "completed");
        if (response.getUsage() != null) {
            body.put("usage", usage(response.getUsage()));
        }
        return body;
    }

    private boolean isResponseObject(Object raw) {
        if (raw == null) {
            return false;
        }

        try {
            JsonNode node = objectMapper.valueToTree(raw);
            return node.has("id") && node.has("output")
                    && "response".equals(node.path("object").asText());
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean hasApprovalRequest(AgentResponse response) {
        return response.getMessages().stream()
                .flatMap(message -> message.getContents().stream())
                .anyMatch(ToolApprovalRequestContent.class::isInstance);
    }

    private static AgentRunOptions runOptions(
            Map<String, Object> body,
            String userScope,
            String foundryCallId) {
        ChatOptions.Builder chat = ChatOptions.builder();
        stringValue(body.get("model")).ifPresent(chat::modelId);
        // NOTE: conversation / previous_response_id are intentionally NOT set on ChatOptions. The
        // host owns history (see handle); forwarding a gateway-supplied conversation id to the
        // upstream model is exactly what caused "conversation not found" (the model never created
        // it). Threading is reconstructed by prepending stored history to the input.
        AgentRunOptions.Builder run = AgentRunOptions.builder().chatOptions(chat.build());
        run.additionalProperty("hosting.request", Collections.unmodifiableMap(
                new LinkedHashMap<>(body)));
        if (userScope != null && !userScope.isEmpty()) {
            run.additionalProperty("x-agent-user-id", userScope);
        }
        if (foundryCallId != null && !foundryCallId.isEmpty()) {
            run.additionalProperty(
                    "x-agent-foundry-call-id",
                    foundryCallId);
        }
        return run.build();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }

    private static List<ChatMessage> requestMessages(Object input) {
        if (input instanceof String) {
            return Collections.singletonList(ChatMessage.user((String) input));
        }
        if (!(input instanceof Iterable<?>)) {
            return Collections.singletonList(ChatMessage.user(
                    input == null ? "" : input.toString()));
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (Object item : (Iterable<?>) input) {
            if (!(item instanceof Map<?, ?>)) {
                messages.add(ChatMessage.user(String.valueOf(item)));
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;
            String type = value(map.get("type"));
            if ("function_call_output".equals(type)) {
                messages.add(ChatMessage.builder(ChatRole.TOOL)
                        .addContent(new FunctionResultContent(
                                value(map.get("call_id")),
                                value(map.get("output")),
                                false))
                        .build());
            } else if ("function_call".equals(type)) {
                messages.add(ChatMessage.builder(ChatRole.ASSISTANT)
                        .addContent(new FunctionCallContent(
                                value(map.get("call_id")),
                                value(map.get("name")),
                                value(map.get("arguments"))))
                        .build());
            } else if ("tool_approval_response".equals(type)) {
                messages.add(ChatMessage.builder(ChatRole.USER)
                        .addContent(new ToolApprovalResponseContent(
                                value(map.get("request_id")),
                                booleanValue(map.get("approved")),
                                nullableValue(map.get("reason"))))
                        .build());
            } else {
                ChatRole role = role(value(map.get("role")));
                messages.add(ChatMessage.builder(role)
                        .text(contentText(map.get("content")))
                        .build());
            }
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("input must contain at least one message");
        }
        return messages;
    }

    private static String contentText(Object content) {
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof Iterable<?>) {
            StringBuilder text = new StringBuilder();
            for (Object part : (Iterable<?>) content) {
                if (part instanceof Map<?, ?>) {
                    Object value = ((Map<?, ?>) part).get("text");
                    if (value != null) {
                        text.append(value);
                    }
                } else if (part != null) {
                    text.append(part);
                }
            }
            return text.toString();
        }
        return content == null ? "" : content.toString();
    }

    private static ChatRole role(String role) {
        if (role == null) {
            return ChatRole.USER;
        }
        switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case "system":
                return ChatRole.SYSTEM;
            case "developer":
                return ChatRole.DEVELOPER;
            case "assistant":
                return ChatRole.ASSISTANT;
            case "tool":
                return ChatRole.TOOL;
            default:
                return ChatRole.USER;
        }
    }

    private static void addMessageOutput(
            List<Map<String, Object>> output,
            ChatMessage message) {
        List<Map<String, Object>> content = new ArrayList<>();
        message.getContents().forEach(item -> {
            if (item instanceof TextContent) {
                content.add(Map.of(
                        "type", "output_text",
                        "text", ((TextContent) item).getText(),
                        "annotations", Collections.emptyList()));
            } else if (item instanceof FunctionCallContent) {
                output.add(functionCallOutput((FunctionCallContent) item));
            } else if (item instanceof ToolApprovalRequestContent) {
                output.add(approvalRequestOutput(
                        (ToolApprovalRequestContent) item));
            }
        });
        if (!content.isEmpty()) {
            output.add(Map.of(
                    "id", generatedId("msg"),
                    "type", "message",
                    "status", "completed",
                    "role", "assistant",
                    "content", content));
        }
    }

    private static Map<String, Object> functionCallOutput(FunctionCallContent call) {
        return Map.of(
                "id", generatedId("fc"),
                "type", "function_call",
                "status", "completed",
                "call_id", call.getCallId(),
                "name", call.getName(),
                "arguments", call.getArguments());
    }

    private static Map<String, Object> approvalRequestOutput(
            ToolApprovalRequestContent request) {
        FunctionCallContent call = request.getFunctionCall();
        return Map.of(
                "id", request.getRequestId(),
                "type", "tool_approval_request",
                "status", "completed",
                "call_id", call.getCallId(),
                "name", call.getName(),
                "arguments", call.getArguments());
    }

    private static Map<String, Object> responseEnvelope(
            String responseId,
            String model,
            List<Map<String, Object>> output,
            String status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", responseId);
        response.put("object", "response");
        response.put("created_at", Instant.now().getEpochSecond());
        response.put("status", status);
        response.put("model", model);
        response.put("output", output);
        return response;
    }

    /**
     * Merges {@code metadata} into {@code envelope}'s {@code metadata} map (creating it if absent),
     * without clobbering existing keys from the caller. Returns the same envelope for chaining.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> applyMetadata(
            Map<String, Object> envelope, Map<String, Object> metadata) {
        if (envelope == null || metadata == null || metadata.isEmpty()) {
            return envelope;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        Object existing = envelope.get("metadata");
        if (existing instanceof Map<?, ?>) {
            ((Map<?, ?>) existing).forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        merged.putAll(metadata);
        envelope.put("metadata", merged);
        return envelope;
    }

    private static Map<String, Object> usage(Usage usage) {        return Map.of(
                "input_tokens", usage.getInputTokens(),
                "output_tokens", usage.getOutputTokens(),
                "total_tokens", usage.getTotalTokens());
    }

    private static String model(AgentRunOptions options) {
        ChatOptions chat = options.getChatOptions();
        return chat != null && chat.getModelId() != null ? chat.getModelId() : "agent";
    }

    private static java.util.Optional<String> stringValue(Object value) {
        if (value == null) {
            return java.util.Optional.empty();
        }
        String text = value.toString();
        return text.trim().isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(text);
    }

    private static String idValue(Object value) {
        if (value instanceof Map<?, ?>) {
            return stringValue(((Map<?, ?>) value).get("id")).orElse(null);
        }
        return stringValue(value).orElse(null);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean
                ? (Boolean) value
                : value != null && "true".equalsIgnoreCase(value.toString());
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullableValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String generatedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class StreamingResponseWriter
            implements Flow.Subscriber<AgentResponseUpdate> {
        private final PrintWriter writer;
        private final ObjectMapper objectMapper;
        private final AgentRunOptions options;
        private final CountDownLatch completion;
        private final AtomicReference<Throwable> failure;
        private final Map<String, Object> responseMetadata;
        private String responseId = generatedId("resp");
        private final String messageId = generatedId("msg");
        private final StringBuilder text = new StringBuilder();
        private final List<Map<String, Object>> output = new ArrayList<>();

        private Flow.Subscription subscription;
        private Boolean rawMode;
        private boolean normalizedAfterRaw;
        private Integer messageOutputIndex;

        private StreamingResponseWriter(
                PrintWriter writer,
                ObjectMapper objectMapper,
                AgentRunOptions options,
                CountDownLatch completion,
                AtomicReference<Throwable> failure,
                Map<String, Object> responseMetadata) {
            this.writer = writer;
            this.objectMapper = objectMapper;
            this.options = options;
            this.completion = completion;
            this.failure = failure;
            this.responseMetadata = responseMetadata;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized void onNext(AgentResponseUpdate update) {
            try {
                if (update.getContinuationToken() != null
                        && !update.getContinuationToken().isEmpty()) {
                    responseId = update.getContinuationToken();
                } else if (update.getResponseId() != null
                        && !update.getResponseId().isEmpty()) {
                    responseId = update.getResponseId();
                }
                JsonNode raw = rawEvent(update.getRawRepresentation());
                if (rawMode == null) {
                    rawMode = raw != null;
                    if (!rawMode) {
                        emit("response.created", Map.of(
                                "type", "response.created",
                                "response", applyMetadata(responseEnvelope(
                                        responseId,
                                        model(options),
                                        Collections.emptyList(),
                                        "in_progress"), responseMetadata)));
                    }
                }
                if (Boolean.TRUE.equals(rawMode) && raw != null) {
                    captureRawOutput(update);
                    emit(raw.path("type").asText(null), raw);
                    return;
                }
                if (Boolean.TRUE.equals(rawMode) && raw == null) {
                    normalizedAfterRaw = true;
                }
                if (!update.getText().isEmpty()) {
                    int outputIndex = messageOutputIndex();
                    text.append(update.getText());
                    emit("response.output_text.delta", Map.of(
                            "type", "response.output_text.delta",
                            "item_id", messageId,
                            "output_index", outputIndex,
                            "content_index", 0,
                            "delta", update.getText()));
                }
                for (io.github.weidongxu.agentframework.chat.ChatContent content
                        : update.getContents()) {
                    if (content instanceof FunctionCallContent) {
                        emitFunctionCall((FunctionCallContent) content);
                    } else if (content instanceof ToolApprovalRequestContent) {
                        emitApprovalRequest(
                                (ToolApprovalRequestContent) content);
                    }
                }
            } catch (Throwable error) {
                cancel();
                onError(error);
            }
        }

        @Override
        public synchronized void onError(Throwable throwable) {
            if (failure.compareAndSet(null, throwable)) {
                try {
                    emit("response.failed", Map.of(
                            "type", "response.failed",
                            "response", applyMetadata(responseEnvelope(
                                    responseId,
                                    model(options),
                                    Collections.emptyList(),
                                    "failed"), responseMetadata)));
                } catch (IOException ignored) {
                    throwable.addSuppressed(ignored);
                }
                completion.countDown();
            }
        }

        @Override
        public synchronized void onComplete() {
            try {
                if (!Boolean.TRUE.equals(rawMode) || normalizedAfterRaw) {
                    if (rawMode == null) {
                        emit("response.created", Map.of(
                                "type", "response.created",
                                "response", applyMetadata(responseEnvelope(
                                        responseId,
                                        model(options),
                                        Collections.emptyList(),
                                        "in_progress"), responseMetadata)));
                    }
                    if (messageOutputIndex != null) {
                        output.set(
                                messageOutputIndex,
                                messageOutput(messageId, text.toString()));
                    }
                    emit("response.completed", Map.of(
                            "type", "response.completed",
                            "response", applyMetadata(responseEnvelope(
                                    responseId,
                                    model(options),
                                    output,
                                    "completed"), responseMetadata)));
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                completion.countDown();
            }
        }

        private JsonNode rawEvent(Object raw) {
            if (raw == null) {
                return null;
            }
            try {
                JsonNode node = objectMapper.valueToTree(raw);
                String type = node.path("type").asText();
                return type.startsWith("response.") ? node : null;
            } catch (IllegalArgumentException error) {
                return null;
            }
        }

        private void emit(String type, Object data) throws IOException {
            if (type != null && !type.trim().isEmpty()) {
                writer.write("event: " + type + "\n");
            }
            writer.write("data: " + objectMapper.writeValueAsString(data) + "\n\n");
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("Client disconnected while streaming response");
            }
        }

        private int messageOutputIndex() {
            if (messageOutputIndex == null) {
                messageOutputIndex = output.size();
                output.add(messageOutput(messageId, ""));
            }
            return messageOutputIndex;
        }

        private void emitFunctionCall(FunctionCallContent call) throws IOException {
            Map<String, Object> item = functionCallOutput(call);
            int outputIndex = output.size();
            output.add(item);
            emit("response.output_item.added", Map.of(
                    "type", "response.output_item.added",
                    "output_index", outputIndex,
                    "item", item));
            emit("response.function_call_arguments.done", Map.of(
                    "type", "response.function_call_arguments.done",
                    "item_id", item.get("id"),
                    "output_index", outputIndex,
                    "arguments", call.getArguments()));
            emit("response.output_item.done", Map.of(
                    "type", "response.output_item.done",
                    "output_index", outputIndex,
                    "item", item));
        }

        private void captureRawOutput(AgentResponseUpdate update) {
            if (!update.getText().isEmpty()) {
                messageOutputIndex();
                text.append(update.getText());
            }
            for (io.github.weidongxu.agentframework.chat.ChatContent content
                    : update.getContents()) {
                if (content instanceof FunctionCallContent) {
                    FunctionCallContent call = (FunctionCallContent) content;
                    boolean recorded = output.stream().anyMatch(item ->
                            "function_call".equals(item.get("type"))
                                    && call.getCallId().equals(
                                            item.get("call_id")));
                    if (!recorded) {
                        output.add(functionCallOutput(call));
                    }
                }
            }
        }

        private void emitApprovalRequest(
                ToolApprovalRequestContent request) throws IOException {
            Map<String, Object> item = approvalRequestOutput(request);
            int outputIndex = output.size();
            output.add(item);
            emit("response.output_item.added", Map.of(
                    "type", "response.output_item.added",
                    "output_index", outputIndex,
                    "item", item));
            emit("response.output_item.done", Map.of(
                    "type", "response.output_item.done",
                    "output_index", outputIndex,
                    "item", item));
        }

        private static Map<String, Object> messageOutput(
                String messageId,
                String text) {
            return Map.of(
                    "id", messageId,
                    "type", "message",
                    "status", "completed",
                    "role", "assistant",
                    "content", Collections.singletonList(Map.of(
                            "type", "output_text",
                            "text", text,
                            "annotations", Collections.emptyList())));
        }

        private synchronized void cancel() {
            if (subscription != null) {
                subscription.cancel();
            }
        }

        /** The response id emitted to the client (from raw events or a generated fallback). */
        private synchronized String responseId() {
            return responseId;
        }

        /** The accumulated assistant text, used to persist the turn for server-side threading. */
        private synchronized String finalText() {
            return text.toString();
        }
    }
}
