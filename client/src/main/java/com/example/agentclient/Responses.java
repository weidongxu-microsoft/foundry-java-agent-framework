package com.example.agentclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

/**
 * Wire + JSON layer for the hosted-agent Responses protocol: issues a Responses call and exposes
 * small read helpers over the returned JSON (output items, annotations, assistant text, response
 * metadata). Kept separate from the scenario assertions in {@link AgentTests}.
 */
final class Responses {

    private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

    /**
     * Model deployment name sent on each Responses call. A hosted agent supplies its own model, so
     * the container mostly ignores this — but the Azure data plane still validates it and returns
     * {@code 500} for an unknown deployment, so it must match the project's actual deployment
     * (here {@code gpt-5-4}, not {@code gpt-5.4}). Overridable via the {@code MODEL} env var.
     */
    static final String MODEL = Env.env("MODEL", "gpt-5-4");

    private Responses() {
    }

    static JsonNode invoke(OpenAIClient client, String input) {
        return invoke(client, input, null);
    }

    static JsonNode invoke(OpenAIClient client, String input, String previousResponseId) {
        // The hosted agent is a coded agent that supplies its own model/instructions/tools; a model
        // value is required by the Responses schema but is ignored by the container. When
        // previousResponseId is set, the agent threads it so the model replays earlier turns.
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .input(input)
                .model(MODEL);
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            builder.previousResponseId(previousResponseId);
        }
        Response response = client.responses().create(builder.build());
        try {
            return MAPPER.readTree(MAPPER.writeValueAsString(response));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Response", e);
        }
    }

    /** True if any item in {@code output[]} has the given {@code type}. */
    static boolean hasOutputOfType(JsonNode response, String type) {
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return false;
        }
        for (JsonNode item : output) {
            JsonNode t = item.get("type");
            if (t != null && type.equals(t.asText())) {
                return true;
            }
        }
        return false;
    }

    /** True if any message content annotation has the given {@code type} (e.g. url_citation). */
    static boolean hasAnnotationOfType(JsonNode response, String type) {
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return false;
        }
        for (JsonNode item : output) {
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                JsonNode annotations = part.get("annotations");
                if (annotations == null || !annotations.isArray()) {
                    continue;
                }
                for (JsonNode ann : annotations) {
                    JsonNode t = ann.get("type");
                    if (t != null && type.equals(t.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Concatenates all {@code output_text} parts of the assistant message(s). */
    static String answerText(JsonNode response) {
        StringBuilder sb = new StringBuilder();
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return "";
        }
        for (JsonNode item : output) {
            JsonNode type = item.get("type");
            if (type == null || !"message".equals(type.asText())) {
                continue;
            }
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                JsonNode partType = part.get("type");
                JsonNode text = part.get("text");
                if (partType != null && "output_text".equals(partType.asText()) && text != null) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text.asText());
                }
            }
        }
        return sb.toString().trim();
    }

    /** Reads a top-level string field (e.g. the response {@code id}); null if absent. */
    static String textOf(JsonNode response, String field) {
        JsonNode n = response.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    /** Returns the string value of {@code metadata.<key>} on the response, or {@code null}. */
    static String metadataValue(JsonNode response, String key) {
        JsonNode metadata = response == null ? null : response.get("metadata");
        if (metadata == null || metadata.isMissingNode() || metadata.isNull()) {
            return null;
        }
        JsonNode value = metadata.get(key);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * Reports the server-side chat-client backend that served the turn, read from the response's
     * {@code metadata} (stamped by the hosted agent). Makes the {@code langchain4j} bridge
     * distinguishable from the default {@code foundry} path in an end-to-end run.
     */
    static String backendLabel(JsonNode response) {
        String backend = metadataValue(response, "chat_client");
        if (backend == null) {
            return "unknown (no metadata)";
        }
        String model = metadataValue(response, "chat_model");
        return (model == null || model.isEmpty()) ? backend : backend + " (model=" + model + ")";
    }

    /**
     * True if the response carries a non-empty {@code metadata.chat_client} and, when
     * {@code expected} is non-blank, it matches (case-insensitive).
     */
    static boolean backendMatches(JsonNode response, String expected) {
        String backend = metadataValue(response, "chat_client");
        boolean present = backend != null && !backend.isBlank()
                && !backend.startsWith("unknown");
        boolean matches = expected == null || expected.isBlank()
                || expected.trim().equalsIgnoreCase(backend);
        return present && matches;
    }
}
