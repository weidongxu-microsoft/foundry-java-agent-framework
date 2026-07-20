package demo.photoprocess.withoutframework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.UUID;

/**
 * The Responses wire format, by hand. This is the counterpart to the framework's protocol layer
 * (its {@code AgentResponseHandler} / response mappers): parsing the inbound request items and
 * building the outbound Response object. In the framework project none of this is app code.
 *
 * <ul>
 *   <li>{@link #extractText}/{@link #extractImage} dig the user text and attached image out of the
 *       {@code input} items ({@code input_text} / {@code input_image} / {@code input_file});</li>
 *   <li>{@link #responseObject} builds a minimal but valid Responses object carrying the reply;</li>
 *   <li>{@link #stripFences} unwraps an optional ```json fence the model may add.</li>
 * </ul>
 */
final class ResponsesJson {

    private ResponsesJson() {
    }

    /** Concatenates the {@code input_text}/{@code output_text} pieces of a Responses input/output. */
    static String extractText(JsonNode input) {
        StringBuilder text = new StringBuilder();
        if (input == null || input.isNull()) {
            return "";
        }
        if (input.isTextual()) {
            return input.asText();
        }
        for (JsonNode item : input) {
            JsonNode content = item.path("content");
            if (content.isArray()) {
                for (JsonNode part : content) {
                    String type = part.path("type").asText();
                    if ("input_text".equals(type) || "output_text".equals(type)
                            || "text".equals(type)) {
                        text.append(part.path("text").asText());
                    }
                }
            } else if ("message".equals(item.path("type").asText()) && content.isTextual()) {
                text.append(content.asText());
            }
        }
        return text.toString();
    }

    /** Finds the first attached image ({@code input_image} data URL or {@code input_file}). */
    static Attachment extractImage(JsonNode input) {
        if (input == null || !input.isArray()) {
            return null;
        }
        for (JsonNode item : input) {
            JsonNode content = item.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                String type = part.path("type").asText();
                if ("input_image".equals(type)) {
                    Attachment a = fromDataUri(imageUrl(part.path("image_url")), "image.jpg");
                    if (a != null) {
                        return a;
                    }
                } else if ("input_file".equals(type)) {
                    String name = part.path("filename").asText("image.jpg");
                    Attachment a = fromDataUri(part.path("file_data").asText(null), name);
                    if (a != null) {
                        return a;
                    }
                }
            }
        }
        return null;
    }

    /** {@code image_url} may be a bare string or an object with a {@code url} field. */
    private static String imageUrl(JsonNode imageUrl) {
        if (imageUrl.isTextual()) {
            return imageUrl.asText();
        }
        return imageUrl.path("url").asText(null);
    }

    /** Decodes a {@code data:<media>;base64,<payload>} URL into bytes + media type. */
    private static Attachment fromDataUri(String dataUri, String fallbackName) {
        if (dataUri == null || !dataUri.startsWith("data:")) {
            return null;
        }
        int comma = dataUri.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String header = dataUri.substring(5, comma);          // e.g. "image/jpeg;base64"
        String payload = dataUri.substring(comma + 1);
        String mediaType = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
        if (mediaType.isBlank()) {
            mediaType = "image/jpeg";
        }
        return new Attachment(Base64.getDecoder().decode(payload), mediaType, fallbackName);
    }

    /** Builds a minimal but valid Responses object carrying the assistant text. */
    static ObjectNode responseObject(ObjectMapper mapper, String model, String assistantText,
            String status) {
        ObjectNode response = mapper.createObjectNode();
        response.put("id", "resp_" + UUID.randomUUID().toString().replace("-", ""));
        response.put("object", "response");
        response.put("created_at", System.currentTimeMillis() / 1000L);
        response.put("status", status);
        response.put("model", model);

        ObjectNode outputText = mapper.createObjectNode();
        outputText.put("type", "output_text");
        outputText.put("text", assistantText);
        outputText.set("annotations", mapper.createArrayNode());

        ObjectNode messageItem = mapper.createObjectNode();
        messageItem.put("type", "message");
        messageItem.put("id", "msg_out");
        messageItem.put("role", "assistant");
        messageItem.put("status", status);
        messageItem.set("content", mapper.createArrayNode().add(outputText));

        ArrayNode output = mapper.createArrayNode();
        output.add(messageItem);
        response.set("output", output);
        return response;
    }

    /** Strips an optional ```json … ``` fence the model may wrap the JSON in. */
    static String stripFences(String text) {
        if (text == null) {
            return "{}";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
