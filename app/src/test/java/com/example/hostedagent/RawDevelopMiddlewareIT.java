package com.example.hostedagent;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.DataContent;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.weidongxu.photo.RawTherapeeDeveloper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end develop through the app pipeline: a user message carrying a real camera-RAW
 * {@link DataContent} runs through {@link RawDevelopMiddleware}, which must develop it to a JPEG and
 * return it as a {@code data:} URL <em>without</em> ever invoking the model ({@code next}).
 *
 * <p>Gated on the same env vars as the {@code raw-photo} library's IT so unit builds stay
 * hermetic:</p>
 * <pre>
 * $env:RAWTHERAPEE_CLI="C:\Program Files\RawTherapee\5.12\rawtherapee-cli.exe"
 * $env:RAW_SAMPLE="C:\path\to\sample.RAF"
 * mvn -f app/pom.xml "-Dtest=RawDevelopMiddlewareIT" test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RAWTHERAPEE_CLI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAW_SAMPLE", matches = ".+")
class RawDevelopMiddlewareIT {

    @Test
    void developsAttachedRawAndShortCircuitsModel() throws Exception {
        Path sample = Paths.get(System.getenv("RAW_SAMPLE"));
        DataContent raw = new DataContent(
                Files.readAllBytes(sample), "image/x-fuji-raf", sample.getFileName().toString());
        ChatMessage user = ChatMessage.builder(ChatRole.USER)
                .text("please develop this")
                .addContent(raw)
                .build();
        AgentMiddlewareContext context = new AgentMiddlewareContext(
                THROWING_AGENT, List.of(user), null, null, false);

        RawDevelopMiddleware middleware =
                new RawDevelopMiddleware(new RawTherapeeDeveloper(), 1024, Runnable::run);

        AgentResponse response = middleware.invoke(context, ctx -> {
            throw new AssertionError("model must not be invoked for a RAW attachment");
        }).toCompletableFuture().join();

        String text = response.getText();
        assertTrue(text.contains("data:image/jpeg;base64,"), "expected a JPEG data URL");
        // The base64 payload is non-trivial (a developed 1024px JPEG is tens of KB).
        int start = text.indexOf("base64,") + "base64,".length();
        assertTrue(text.length() - start > 10_000, "expected a non-empty developed JPEG");
        assertFalse(text.contains("could not develop"), "develop should have succeeded");
    }

    @Test
    void appliesVisionSuggestedCropToTheDevelopedJpeg() throws Exception {
        Path sample = Paths.get(System.getenv("RAW_SAMPLE"));
        DataContent raw = new DataContent(
                Files.readAllBytes(sample), "image/x-fuji-raf", sample.getFileName().toString());
        ChatMessage user = ChatMessage.builder(ChatRole.USER)
                .text("please develop this")
                .addContent(raw)
                .build();
        AgentMiddlewareContext context = new AgentMiddlewareContext(
                THROWING_AGENT, List.of(user), null, null, false);

        // Stub the vision model to always return a 60% x 60% crop plus a mild exposure bump.
        ChatClient stub = new ChatClient() {
            @Override
            public CompletionStage<ChatResponse> getResponse(
                    List<ChatMessage> messages, ChatOptions options) {
                String json = "{\"exposure_ev\":0.3,"
                        + "\"crop\":{\"left\":0.1,\"top\":0.1,\"right\":0.7,\"bottom\":0.7}}";
                return CompletableFuture.completedFuture(ChatResponse.builder()
                        .message(ChatMessage.builder(ChatRole.ASSISTANT).text(json).build())
                        .build());
            }

            @Override
            public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                    List<ChatMessage> messages, ChatOptions options) {
                throw new UnsupportedOperationException();
            }
        };

        int maxLongEdge = 1024;
        RawDevelopMiddleware middleware = new RawDevelopMiddleware(
                new RawTherapeeDeveloper(), maxLongEdge, Runnable::run,
                stub, new ObjectMapper(), "gpt-test", true, 512, true);

        AgentResponse response = middleware.invoke(context, ctx -> {
            throw new AssertionError("model must not be invoked for a RAW attachment");
        }).toCompletableFuture().join();

        String text = response.getText();
        assertTrue(text.contains("AI-suggested adjustments"), "expected advised develop");
        assertTrue(text.contains("Applied crop:"), "expected the crop note");
        assertTrue(text.contains("auto lens correction"), "expected lens correction note");

        int start = text.indexOf("base64,") + "base64,".length();
        int end = text.indexOf('\n', start);
        String b64 = (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
        assertNotNull(img, "expected a decodable cropped JPEG");
        int longEdge = Math.max(img.getWidth(), img.getHeight());
        // A 60% crop of a develop capped at 1024px long edge must be clearly under that cap.
        assertTrue(longEdge < maxLongEdge, "cropped long edge " + longEdge + " should be < " + maxLongEdge);
        assertTrue(longEdge > 200, "cropped image should still be reasonably sized");
    }

    private static final Agent THROWING_AGENT = new Agent() {
        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }
    };
}
