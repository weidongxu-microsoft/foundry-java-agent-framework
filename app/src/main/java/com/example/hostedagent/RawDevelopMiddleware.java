package com.example.hostedagent;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.DataContent;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.middleware.AgentMiddleware;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.AgentStreamingMiddlewareNext;
import io.github.weidongxu.photo.DevelopSettings;
import io.github.weidongxu.photo.RawDevelopException;
import io.github.weidongxu.photo.RawDeveloper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

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
 * <p>Item #1 (this pass) develops a <em>neutral</em> JPEG downscaled to {@code maxLongEdgePx}. The
 * adjustment + vision-advice steps (items #2/#3) build on the same seam later.</p>
 */
final class RawDevelopMiddleware implements AgentMiddleware {

    private static final Logger LOG = LoggerFactory.getLogger(RawDevelopMiddleware.class);

    /** Media-type fragments / filename suffixes recognised as camera RAW. */
    private static final Set<String> RAW_SUFFIXES = Set.of(
            ".raf", ".cr2", ".cr3", ".nef", ".arw", ".dng", ".rw2", ".orf", ".raw", ".pef", ".srw");

    private final RawDeveloper developer;
    private final Integer maxLongEdgePx;
    private final Executor executor;

    RawDevelopMiddleware(RawDeveloper developer, Integer maxLongEdgePx, Executor executor) {
        this.developer = developer;
        this.maxLongEdgePx = maxLongEdgePx;
        this.executor = executor;
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

            DevelopSettings.Builder settings = DevelopSettings.builder();
            if (maxLongEdgePx != null) {
                settings.maxLongEdgePx(maxLongEdgePx);
            }
            developer.develop(rawPath, settings.build(), jpegPath);

            byte[] jpeg = Files.readAllBytes(jpegPath);
            DataContent output = new DataContent(jpeg, "image/jpeg", baseName(name) + ".jpg");
            String note = "Developed **" + name + "** to a neutral JPEG ("
                    + (jpeg.length / 1024) + " KB"
                    + (maxLongEdgePx != null ? ", max " + maxLongEdgePx + "px long edge" : "")
                    + "). The image is returned below as a data URL.\n\n" + output.toDataUri();
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
