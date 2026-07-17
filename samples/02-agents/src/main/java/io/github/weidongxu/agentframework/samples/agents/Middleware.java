package io.github.weidongxu.agentframework.samples.agents;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.impl.ChatClientAgent;
import io.github.weidongxu.agentframework.middleware.AgentMiddleware;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareNext;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sample 02 — agent middleware.
 *
 * <p>An {@link AgentMiddleware} wraps every agent run: it can inspect or mutate the request, then
 * call {@code next.invoke(context)} to continue the pipeline, and observe the response. Here a
 * simple middleware logs the incoming message count and the run's wall-clock time.
 *
 * <pre>{@code
 *   mvn -q -f samples\02-agents\pom.xml compile ^
 *       exec:java -Dexec.mainClass=io.github.weidongxu.agentframework.samples.agents.Middleware
 * }</pre>
 */
public final class Middleware {

    private Middleware() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = Support.requireApiKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ChatClient chatClient = Support.chatClient(apiKey, executor);

            Agent agent = ChatClientAgent.builder(chatClient)
                    .name("timed-agent")
                    .instructions("You are a concise assistant. Answer in one short sentence.")
                    .chatOptions(ChatOptions.builder().modelId(Support.model()).build())
                    .middleware(new TimingMiddleware())
                    .build();

            String question = args.length > 0
                    ? String.join(" ", args)
                    : "What is a software design pattern?";
            System.out.println("Q: " + question);

            AgentResponse response = agent.run(question).toCompletableFuture().get();
            System.out.println("A: " + response.getText());
        } finally {
            executor.shutdown();
        }
    }

    /** Logs the request size and elapsed time around each run. */
    private static final class TimingMiddleware implements AgentMiddleware {
        @Override
        public CompletionStage<AgentResponse> invoke(
                AgentMiddlewareContext context, AgentMiddlewareNext next) {
            long start = System.nanoTime();
            System.out.println("[middleware] run starting with "
                    + context.getMessages().size() + " message(s)");
            return next.invoke(context).whenComplete((response, error) -> {
                long millis = (System.nanoTime() - start) / 1_000_000;
                System.out.println("[middleware] run finished in " + millis + " ms"
                        + (error != null ? " (error: " + error.getMessage() + ")" : ""));
            });
        }
    }
}
