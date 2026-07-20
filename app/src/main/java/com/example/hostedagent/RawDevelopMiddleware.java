package com.example.hostedagent;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatClient;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.weidongxu.photo.DevelopSettings;
import io.github.weidongxu.photo.RawDevelopException;
import io.github.weidongxu.photo.RawDeveloper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * App-owned RAW-photo pipeline, wired as agent {@link AgentMiddleware}. When the incoming user
 * message carries a camera RAW {@link DataContent}, it develops the RAW to a JPEG with the
 * framework-independent {@code raw-photo} library and returns that JPEG to the user, short-circuiting
 * the model entirely (a 24MB RAW is not something a text model can consume).
 *
 * <p>This is the "real tool" that replaces the demo {@code TodoTool}. It exercises three multimodal
 * capabilities end-to-end: binary input ({@link DataContent} parsed from a Responses
 * {@code input_file}), host-side processing of bytes the model never sees, and binary output
 * (the developed JPEG returned as a {@code data:} URL inside {@code output_text}, per plan/19 —
 * the delivery format that passes cleanly through the Foundry gateway).</p>
 *
 * <p>Item #1 develops a <em>neutral</em> JPEG downscaled to {@code maxLongEdgePx}. Items #2/#3 add a
 * vision-advice loop: when {@code adviceEnabled} and a {@link ChatClient} is wired, a small neutral
 * preview is shown to the model, which returns adjustment values (white balance, exposure, contrast,
 * etc.) as JSON; the RAW is then re-developed with those adjustments at output size. Any failure
 * (advice disabled, model error, unparsable JSON) falls back to the neutral develop — the turn never
 * fails.</p>
 */
final class RawDevelopMiddleware implements AgentMiddleware {

    private static final Logger LOG = LoggerFactory.getLogger(RawDevelopMiddleware.class);

    /** Media-type fragments / filename suffixes recognised as camera RAW. */
    private static final Set<String> RAW_SUFFIXES = Set.of(
            ".raf", ".cr2", ".cr3", ".nef", ".arw", ".dng", ".rw2", ".orf", ".raw", ".pef", ".srw");

    /** Default long edge of the neutral preview shown to the vision model. */
    private static final int DEFAULT_ADVICE_LONG_EDGE_PX = 1024;

    /** How long to wait for the vision advice sub-call before falling back to neutral. */
    private static final Duration ADVICE_TIMEOUT = Duration.ofSeconds(120);

    private static final String ADVICE_SYSTEM =
            "You are a professional photo-editing assistant. You are shown a neutrally developed "
                    + "camera photo. Suggest tasteful, natural-looking global adjustments to improve it. "
                    + "Respond with ONLY a JSON object (no prose, no code fences) using any of these "
                    + "optional keys: white_balance_temp_k (integer Kelvin, ~2500-9000), tint (double, "
                    + "1.0 neutral, >1 greener), exposure_ev (double stops, e.g. -1.0..1.0), contrast "
                    + "(integer -100..100), saturation (integer -100..100), highlights (integer "
                    + "-100..100, positive recovers blown highlights), shadows (integer -100..100, "
                    + "positive lifts shadows). Omit any key you would leave unchanged.";

    private static final String ADVICE_USER =
            "Suggest adjustment values to develop this photo well. Return only the JSON object.";

    private final RawDeveloper developer;
    private final Integer maxLongEdgePx;
    private final Executor executor;
    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final String model;
    private final boolean adviceEnabled;
    private final Integer adviceLongEdgePx;
    private final boolean lensCorrection;

    RawDevelopMiddleware(RawDeveloper developer, Integer maxLongEdgePx, Executor executor) {
        this(developer, maxLongEdgePx, executor, null, null, null, false, null, false);
    }

    RawDevelopMiddleware(
            RawDeveloper developer,
            Integer maxLongEdgePx,
            Executor executor,
            ChatClient chatClient,
            ObjectMapper mapper,
            String model,
            boolean adviceEnabled,
            Integer adviceLongEdgePx,
            boolean lensCorrection) {
        this.developer = developer;
        this.maxLongEdgePx = maxLongEdgePx;
        this.executor = executor;
        this.chatClient = chatClient;
        this.mapper = mapper;
        this.model = model;
        this.adviceEnabled = adviceEnabled;
        this.adviceLongEdgePx = adviceLongEdgePx;
        this.lensCorrection = lensCorrection;
    }

    @Override
    public CompletionStage<AgentResponse> invoke(
            AgentMiddlewareContext context, AgentMiddlewareNext next) {
        DataContent raw = findRaw(context);
        if (raw == null) {
            return next.invoke(context);
        }
        return CompletableFuture.supplyAsync(
                () -> AgentResponse.builder().message(develop(raw)).finishReason(FinishReason.STOP).build(),
                executor);
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> invokeStreaming(
            AgentMiddlewareContext context, AgentStreamingMiddlewareNext next) {
        DataContent raw = findRaw(context);
        if (raw == null) {
            return next.invoke(context);
        }
        ChatMessage message = develop(raw);
        AgentResponseUpdate update = AgentResponseUpdate.builder()
                .role(ChatRole.ASSISTANT)
                .contents(message.getContents())
                .finishReason(FinishReason.STOP)
                .build();
        return singleItem(update);
    }

    /** @return the first camera-RAW attachment on the last user message, or {@code null}. */
    private static DataContent findRaw(AgentMiddlewareContext context) {
        List<ChatMessage> messages = context.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() != ChatRole.USER) {
                continue;
            }
            for (io.github.weidongxu.agentframework.chat.ChatContent content : message.getContents()) {
                if (content instanceof DataContent && isRaw((DataContent) content)) {
                    return (DataContent) content;
                }
            }
            return null;
        }
        return null;
    }

    private static boolean isRaw(DataContent data) {
        String name = data.getName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String suffix : RAW_SUFFIXES) {
                if (lower.endsWith(suffix)) {
                    return true;
                }
            }
        }
        String mediaType = data.getMediaType().toLowerCase(Locale.ROOT);
        return mediaType.contains("raf") || mediaType.contains("raw")
                || mediaType.contains("x-adobe-dng");
    }

    private ChatMessage develop(DataContent raw) {
        Path work = null;
        try {
            work = Files.createTempDirectory("raw-develop");
            String name = raw.getName() != null ? sanitize(raw.getName()) : "input.raf";
            Path rawPath = work.resolve(name);
            Files.write(rawPath, raw.getData());
            Path jpegPath = work.resolve("developed.jpg");

            AdviceResult advice = (adviceEnabled && chatClient != null)
                    ? adviseSettings(work, rawPath)
                    : null;

            DevelopSettings settings;
            if (advice != null) {
                settings = advice.settings.toBuilder().lensCorrection(lensCorrection).build();
            } else {
                DevelopSettings.Builder b = DevelopSettings.builder().lensCorrection(lensCorrection);
                if (maxLongEdgePx != null) {
                    b.maxLongEdgePx(maxLongEdgePx);
                }
                settings = b.build();
            }
            developer.develop(rawPath, settings, jpegPath);

            byte[] jpeg = Files.readAllBytes(jpegPath);
            DataContent output = new DataContent(jpeg, "image/jpeg", baseName(name) + ".jpg");
            String note;
            if (advice != null) {
                note = "Developed **" + name + "** with AI-suggested adjustments ("
                        + (jpeg.length / 1024) + " KB"
                        + (maxLongEdgePx != null ? ", max " + maxLongEdgePx + "px long edge" : "")
                        + (lensCorrection ? ", auto lens correction" : "")
                        + ").\n\nApplied adjustments: `" + advice.json + "`\n\n" + output.toDataUri();
            } else {
                note = "Developed **" + name + "** to a neutral JPEG ("
                        + (jpeg.length / 1024) + " KB"
                        + (maxLongEdgePx != null ? ", max " + maxLongEdgePx + "px long edge" : "")
                        + (lensCorrection ? ", auto lens correction" : "")
                        + "). The image is returned below as a data URL.\n\n" + output.toDataUri();
            }
            return ChatMessage.builder(ChatRole.ASSISTANT).text(note).build();
        } catch (RawDevelopException | java.io.IOException error) {
            LOG.warn("RAW develop failed", error);
            return ChatMessage.builder(ChatRole.ASSISTANT)
                    .text("Sorry — I could not develop that RAW file: " + error.getMessage())
                    .build();
        } finally {
            deleteQuietly(work);
        }
    }

    /**
     * Develops a small neutral preview, asks the vision model for adjustment values, and parses them
     * into {@link DevelopSettings}. The output size ({@code maxLongEdgePx}) is forced onto the result
     * so the model cannot dictate the final resolution. Returns {@code null} on any failure (the
     * caller then falls back to a neutral develop).
     */
    private AdviceResult adviseSettings(Path work, Path rawPath) {
        try {
            int adviceEdge = adviceLongEdgePx != null
                    ? adviceLongEdgePx
                    : (maxLongEdgePx != null ? maxLongEdgePx : DEFAULT_ADVICE_LONG_EDGE_PX);
            Path previewPath = work.resolve("preview.jpg");
            developer.develop(
                    rawPath,
                    DevelopSettings.builder()
                            .maxLongEdgePx(adviceEdge)
                            .lensCorrection(lensCorrection)
                            .build(),
                    previewPath);
            DataContent preview =
                    new DataContent(Files.readAllBytes(previewPath), "image/jpeg", "preview.jpg");

            List<ChatMessage> messages = Arrays.asList(
                    ChatMessage.builder(ChatRole.DEVELOPER)
                            .addContent(new TextContent(ADVICE_SYSTEM))
                            .build(),
                    ChatMessage.builder(ChatRole.USER)
                            .addContent(new TextContent(ADVICE_USER))
                            .addContent(preview)
                            .build());
            ChatOptions options = ChatOptions.builder()
                    .modelId(model)
                    .responseFormat(ResponseFormat.jsonObject())
                    .build();
            ChatResponse response = chatClient.getResponse(messages, options)
                    .toCompletableFuture()
                    .get(ADVICE_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

            String json = stripFences(response.getText());
            JsonNode node = mapper.readTree(json);
            if (!(node instanceof ObjectNode)) {
                LOG.warn("Vision advice returned non-object JSON; falling back to neutral");
                return null;
            }
            ObjectNode object = (ObjectNode) node;
            object.remove("max_long_edge_px");
            if (maxLongEdgePx != null) {
                object.put("max_long_edge_px", maxLongEdgePx.intValue());
            }
            return new AdviceResult(DevelopSettings.fromJsonNode(object), object.toString());
        } catch (Exception error) {
            LOG.warn("Vision advice failed; falling back to neutral develop: {}", error.toString());
            return null;
        }
    }

    /** Strips an optional ```json … ``` code fence the model may wrap the JSON in. */
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

    /** Parsed vision advice plus the JSON string (for the user-facing note). */
    private static final class AdviceResult {
        private final DevelopSettings settings;
        private final String json;

        AdviceResult(DevelopSettings settings, String json) {
            this.settings = settings;
            this.json = json;
        }
    }

    private static String sanitize(String name) {
        String base = name.replaceAll("[\\\\/]", "_");
        return base.isEmpty() ? "input.raf" : base;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // best effort
                }
            });
        } catch (java.io.IOException ignored) {
            // best effort
        }
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
