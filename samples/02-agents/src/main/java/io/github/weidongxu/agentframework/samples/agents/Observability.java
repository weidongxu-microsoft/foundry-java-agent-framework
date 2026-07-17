package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.impl.MiddlewareChatClient;
import io.github.weidongxu.agentframework.observability.opentelemetry.OpenTelemetryAgentMiddleware;
import io.github.weidongxu.agentframework.observability.opentelemetry.OpenTelemetryChatMiddleware;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — observability with OpenTelemetry.
 *
 * <p>Instrument both layers: wrap the {@link ChatClient} with {@link OpenTelemetryChatMiddleware}
 * (chat spans) and add {@link OpenTelemetryAgentMiddleware} to the agent (an {@code invoke_agent}
 * span that parents the chat span). This sample exports finished spans to the console.
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.Observability
 * }</pre>
 */
public final class Observability {

    private Observability() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OpenTelemetrySdk openTelemetry = consoleOpenTelemetry();
        try {
            ChatClient base = Support.chatClient(apiKey, executor);
            ChatClient instrumented = new MiddlewareChatClient(
                    base, Collections.singletonList(new OpenTelemetryChatMiddleware(openTelemetry)));

            Agent agent = ChatClientAgent.builder(instrumented)
                    .id("agent-observability")
                    .name("observability-agent")
                    .instructions("You are a concise assistant. Answer in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .middleware(new OpenTelemetryAgentMiddleware(openTelemetry))
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "In one sentence, what is distributed tracing?";
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
            System.out.println("(spans printed above as they finished)");
        } finally {
            openTelemetry.close();
            executor.shutdown();
        }
    }

    /** A minimal OpenTelemetry SDK that prints each finished span to stdout. */
    private static OpenTelemetrySdk consoleOpenTelemetry() {
        SpanExporter consoleExporter = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                for (SpanData span : spans) {
                    System.out.println("SPAN " + span.getName()
                            + " (" + span.getAttributes().size() + " attributes)");
                }
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(consoleExporter))
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }
}
