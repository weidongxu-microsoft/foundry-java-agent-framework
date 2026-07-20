package demo.photoprocess.withoutframework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A hand-written model client — the counterpart to the framework's {@code OpenAIResponsesChatClient}
 * (which the with-framework project injects as a {@code ChatClient} and never implements). Here you
 * assemble the Responses request JSON by hand, POST it to the model's {@code /responses} endpoint
 * over {@code java.net.http}, and either parse the buffered output or hand back the model's own SSE
 * stream for the controller to relay.
 *
 * <ul>
 *   <li>{@link #completeJson} — buffered, image + strict {@code json_object} (the crop-advice call);</li>
 *   <li>{@link #completeText} — buffered prose (the non-streaming chat turn);</li>
 *   <li>{@link #openChatStream} — a <em>streaming</em> chat turn: returns the model's own SSE frames
 *       as lines, which {@link PhotoProcessController} relays verbatim.</li>
 * </ul>
 */
final class ModelClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper mapper;
    private final String model;
    private final String baseUrl;
    private final String apiKey;

    ModelClient(ObjectMapper mapper, String model, String baseUrl, String apiKey) {
        this.mapper = mapper;
        this.model = model;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
    }

    /** Buffered call with an image and strict {@code json_object} output — the crop-advice call. */
    String completeJson(String system, String userText, Attachment image) throws Exception {
        JsonNode response = postResponses(buildRequest(system, userText, image, false, true));
        return ResponsesJson.extractText(response.path("output"));
    }

    /** Buffered prose call (no image) — the non-streaming chat turn. */
    String completeText(String system, String userText) throws Exception {
        JsonNode response = postResponses(buildRequest(system, userText, null, false, false));
        return ResponsesJson.extractText(response.path("output"));
    }

    /**
     * Opens a STREAMING Responses call and returns the model's own SSE frames as a line stream. The
     * model's Responses stream is already named ({@code event: response.output_text.delta} …) and
     * terminates on {@code response.completed}, so the controller can relay it verbatim — this is the
     * "relay the model's own SSE frames" approach the framework's ChatClient does internally.
     */
    Stream<String> openChatStream(String system, String userText) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/responses"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + safe(apiKey))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(buildRequest(system, userText, null, true, false))))
                .build();
        HttpResponse<Stream<String>> response =
                http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() / 100 != 2) {
            String body = response.body().collect(Collectors.joining("\n"));
            throw new RuntimeException("Responses API " + response.statusCode() + ": " + body);
        }
        return response.body();
    }

    /** Builds one Responses request body item-by-item — no SDK to do it for you. */
    private ObjectNode buildRequest(String system, String userText, Attachment image,
            boolean stream, boolean jsonObject) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);
        request.put("instructions", system);

        if (image == null) {
            request.put("input", userText);
        } else {
            String dataUri = "data:" + image.mediaType() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.bytes());
            ArrayNode content = mapper.createArrayNode();
            content.add(mapper.createObjectNode().put("type", "input_text").put("text", userText));
            content.add(mapper.createObjectNode().put("type", "input_image").put("image_url", dataUri));
            ObjectNode message = mapper.createObjectNode();
            message.put("role", "user");
            message.set("content", content);
            request.set("input", mapper.createArrayNode().add(message));
        }

        if (stream) {
            request.put("stream", true);
        }
        if (jsonObject) {
            request.set("text", mapper.createObjectNode()
                    .set("format", mapper.createObjectNode().put("type", "json_object")));
        }
        return request;
    }

    private JsonNode postResponses(JsonNode request) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/responses"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + safe(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                .build();
        HttpResponse<String> httpResponse =
                http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() / 100 != 2) {
            throw new RuntimeException(
                    "Responses API " + httpResponse.statusCode() + ": " + httpResponse.body());
        }
        return mapper.readTree(httpResponse.body());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
