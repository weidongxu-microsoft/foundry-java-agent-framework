package com.example.hostedagent;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.DataContent;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.photo.RawTherapeeDeveloper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
