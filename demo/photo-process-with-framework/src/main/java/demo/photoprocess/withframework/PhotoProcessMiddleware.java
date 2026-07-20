package demo.photoprocess.withframework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.DataContent;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.ResponseFormat;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.middleware.AgentMiddleware;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.AgentStreamingMiddlewareNext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * The photo-process workflow, wired as an agent {@link AgentMiddleware}. When the incoming user
 * message carries a JPEG {@link DataContent}, this middleware:
 * <ol>
 *   <li>shows the photo to the vision model asking <em>"how should I crop the photo to make it most
 *       impactful"</em> and requests a strict-JSON crop rectangle,</li>
 *   <li>crops the JPEG with {@link PhotoProcessor} (pure JDK), and</li>
 *   <li>returns the cropped JPEG to the user as a {@code data:} URL, short-circuiting the model's
 *       normal text turn.</li>
 * </ol>
 *
 * <p>Everything the hosted-agent needs beyond this workflow — serving {@code POST /responses},
 * SSE framing, {@code /readiness}, the model call plumbing, and the response object — is provided
 * by the framework (see {@link PhotoProcessConfiguration}). Compare with the without-framework project,
 * where all of that is hand-written.
 */
final class PhotoProcessMiddleware implements AgentMiddleware {

    private static final Logger LOG = LoggerFactory.getLogger(PhotoProcessMiddleware.class);

    private static final Duration ADVICE_TIMEOUT = Duration.ofSeconds(120);

    private static final String CROP_SYSTEM =
            "You are a photo editor. You are shown one photograph and its pixel dimensions. "
                    + "Decide how to crop it to make it the most impactful image: strengthen the "
                    + "composition, remove dead space and distractions, and emphasise the subject "
                    + "(rule of thirds, tighter framing). Respond with STRICT JSON only, no prose, "
                    + "no code fences, of the exact form: "
                    + "{\"x\":int,\"y\":int,\"width\":int,\"height\":int,\"reason\":string}. "
                    + "x,y are the top-left corner and width,height the size of the crop, all in "
                    + "pixels within the given dimensions. Keep the crop inside the image.";

    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final String model;
    private final Executor executor;

    PhotoProcessMiddleware(ChatClient chatClient, ObjectMapper mapper, String model, Executor executor) {
        this.chatClient = chatClient;
        this.mapper = mapper;
        this.model = model;
        this.executor = executor;
    }

    @Override
    public CompletionStage<AgentResponse> invoke(
            AgentMiddlewareContext context, AgentMiddlewareNext next) {
        DataContent photo = findPhoto(context);
        if (photo == null) {
            return next.invoke(context); // not a photo turn — let the normal agent run
        }
        return CompletableFuture.supplyAsync(
                () -> AgentResponse.builder()
                        .message(cropPhoto(photo))
                        .finishReason(FinishReason.STOP)
                        .build(),
                executor);
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> invokeStreaming(
            AgentMiddlewareContext context, AgentStreamingMiddlewareNext next) {
        DataContent photo = findPhoto(context);
        if (photo == null) {
            return next.invoke(context);
        }
        ChatMessage message = cropPhoto(photo);
        AgentResponseUpdate update = AgentResponseUpdate.builder()
                .role(ChatRole.ASSISTANT)
                .contents(message.getContents())
                .finishReason(FinishReason.STOP)
                .build();
        return singleItem(update);
    }

    /** @return the first JPEG attachment on the last user message, or {@code null}. */
    private static DataContent findPhoto(AgentMiddlewareContext context) {
        List<ChatMessage> messages = context.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() != ChatRole.USER) {
                continue;
            }
            for (ChatContent content : message.getContents()) {
                if (content instanceof DataContent data && isJpeg(data)) {
                    return data;
                }
            }
            return null;
        }
        return null;
    }

    private static boolean isJpeg(DataContent data) {
        String mediaType = data.getMediaType() == null
                ? "" : data.getMediaType().toLowerCase(Locale.ROOT);
        if (mediaType.contains("jpeg") || mediaType.contains("jpg")) {
            return true;
        }
        String name = data.getName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        }
        return false;
    }

    private ChatMessage cropPhoto(DataContent photo) {
        try {
            byte[] jpeg = photo.getData();
            PhotoProcessor.Dimensions dims = PhotoProcessor.dimensions(jpeg);

            PhotoProcessor.CropBox box = adviseCrop(photo, dims);
            byte[] cropped = PhotoProcessor.crop(jpeg, box);

            DataContent output = new DataContent(cropped, "image/jpeg", outputName(photo));
            String note = "Cropped **" + safeName(photo) + "** to make it more impactful "
                    + "(" + box.width() + "\u00d7" + box.height() + " px from a "
                    + dims.width() + "\u00d7" + dims.height() + " px original).\n\n"
                    + "**Why:** " + box.reason() + "\n\n"
                    + output.toDataUri();
            return ChatMessage.builder(ChatRole.ASSISTANT).text(note).build();
        } catch (Exception error) {
            LOG.warn("Crop failed", error);
            return ChatMessage.builder(ChatRole.ASSISTANT)
                    .text("Sorry — I could not crop that photo: " + error.getMessage())
                    .build();
        }
    }

    /** Asks the vision model how to crop; parses the strict-JSON rectangle. */
    private PhotoProcessor.CropBox adviseCrop(DataContent photo, PhotoProcessor.Dimensions dims)
            throws Exception {
        String userText = "How should I crop this photo to make it most impactful? "
                + "The image is " + dims.width() + "\u00d7" + dims.height() + " pixels. "
                + "Return the crop rectangle in pixels as JSON.";

        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.builder(ChatRole.DEVELOPER)
                        .addContent(new TextContent(CROP_SYSTEM))
                        .build(),
                ChatMessage.builder(ChatRole.USER)
                        .addContent(new TextContent(userText))
                        .addContent(photo)
                        .build());
        ChatOptions options = ChatOptions.builder()
                .modelId(model)
                .responseFormat(ResponseFormat.jsonObject())
                .build();

        ChatResponse response = chatClient.getResponse(messages, options)
                .toCompletableFuture()
                .get(ADVICE_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

        JsonNode node = mapper.readTree(stripFences(response.getText()));
        int x = node.path("x").asInt(0);
        int y = node.path("y").asInt(0);
        int width = node.path("width").asInt(dims.width());
        int height = node.path("height").asInt(dims.height());
        String reason = node.path("reason").asText("").trim();
        return new PhotoProcessor.CropBox(x, y, width, height, reason);
    }

    private static String outputName(DataContent photo) {
        String name = safeName(photo);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "-cropped.jpg";
    }

    private static String safeName(DataContent photo) {
        return (photo.getName() == null || photo.getName().isBlank())
                ? "photo.jpg" : photo.getName();
    }

    /** Strips an optional ```json … ``` fence the model may wrap the JSON in. */
    private static String stripFences(String text) {
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

    private static <T> Flow.Publisher<T> singleItem(T item) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public void request(long n) {
                if (!done && n > 0) {
                    done = true;
                    subscriber.onNext(item);
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }
}
