package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import io.github.weidongxu.agentframework.middleware.FunctionInvocationContext;
import io.github.weidongxu.agentframework.middleware.FunctionMiddleware;
import io.github.weidongxu.agentframework.tool.ApprovalMode;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;
import io.github.weidongxu.agentframework.tool.ToolContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionInvokingChatClientTest {
    @Test
    void passesSessionToToolViaContext() throws Exception {
        AtomicReference<String> observedScope = new AtomicReference<>();
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage("call-1", "scope", "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        Tool scopeTool = new Tool() {
            @Override
            public String getName() {
                return "scope";
            }

            @Override
            public String getDescription() {
                return "Reports the ambient session scope";
            }

            @Override
            public Map<String, Object> getParametersSchema() {
                return Collections.emptyMap();
            }

            @Override
            public CompletionStage<String> invoke(Map<String, Object> arguments) {
                return invoke(arguments, ToolContext.empty());
            }

            @Override
            public CompletionStage<String> invoke(
                    Map<String, Object> arguments, ToolContext context) {
                observedScope.set(context.getSession() == null
                        ? null
                        : context.getSession().getId());
                return CompletableFuture.completedFuture("ok");
            }
        };
        ChatOptions options = ChatOptions.builder()
                .tool(scopeTool)
                .additionalProperty(
                        RunContextProperties.AGENT_SESSION,
                        new AgentSession("user-42", Collections.emptyMap()))
                .build();

        client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("user-42", observedScope.get());
    }

    @Test
    void pausesForApprovalAndResumesApprovedToolCall() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage("call-1", "echo", "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> {
                            invocations.incrementAndGet();
                            return CompletableFuture.completedFuture("approved");
                        },
                        ApprovalMode.ALWAYS_REQUIRE))
                .build();

        ChatResponse pending = client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ToolApprovalRequestContent request = assertInstanceOf(
                ToolApprovalRequestContent.class,
                pending.getMessages().get(1).getContents().get(0));
        assertEquals(0, invocations.get());

        ChatResponse completed = client.getResponse(
                        Collections.singletonList(ChatMessage.builder(ChatRole.USER)
                                .addContent(request.approve())
                                .build()),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(1, invocations.get());
        assertEquals("done", completed.getText());
        FunctionResultContent result = assertInstanceOf(
                FunctionResultContent.class,
                inner.requests.get(1).get(2).getContents().get(0));
        assertEquals("approved", result.getResult());
    }

    @Test
    void rejectedApprovalReturnsToolErrorWithoutInvokingTool() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage("call-1", "echo", "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("rejected"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> {
                            invocations.incrementAndGet();
                            return CompletableFuture.completedFuture("unexpected");
                        },
                        ApprovalMode.ALWAYS_REQUIRE))
                .build();
        ToolApprovalRequestContent request = assertInstanceOf(
                ToolApprovalRequestContent.class,
                client.getResponse(
                                Collections.singletonList(ChatMessage.user("start")),
                                options)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .getMessages()
                        .get(1)
                        .getContents()
                        .get(0));

        client.getResponse(
                        Collections.singletonList(ChatMessage.builder(ChatRole.USER)
                                .addContent(request.reject("not allowed"))
                                .build()),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(0, invocations.get());
        FunctionResultContent result = assertInstanceOf(
                FunctionResultContent.class,
                inner.requests.get(1).get(2).getContents().get(0));
        assertTrue(result.isError());
        assertEquals("not allowed", result.getResult());
    }

    @Test
    void rejectsUnknownApprovalResponse() {
        FunctionInvokingChatClient client =
                new FunctionInvokingChatClient(new SequencedChatClient());
        ChatMessage response = ChatMessage.builder(ChatRole.USER)
                .addContent(new ToolApprovalResponseContent(
                        "missing",
                        true,
                        null))
                .build();

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> client.getResponse(
                                Collections.singletonList(response),
                                ChatOptions.builder().build())
                        .toCompletableFuture()
                        .join());

        assertInstanceOf(ToolInvocationException.class, error.getCause());
    }

    @Test
    void requiresCompleteApprovalBatchBeforeExecutingTools() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        ChatMessage calls = ChatMessage.builder(ChatRole.ASSISTANT)
                .addContent(new FunctionCallContent("call-1", "first", "{}"))
                .addContent(new FunctionCallContent("call-2", "second", "{}"))
                .build();
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(calls)
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(approvalTool("first", invocations))
                .tool(approvalTool("second", invocations))
                .build();
        ChatResponse pending = client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ToolApprovalRequestContent first = (ToolApprovalRequestContent)
                pending.getMessages().get(1).getContents().get(0);
        ToolApprovalRequestContent second = (ToolApprovalRequestContent)
                pending.getMessages().get(2).getContents().get(0);

        CompletionException incomplete = assertThrows(
                CompletionException.class,
                () -> client.getResponse(
                                Collections.singletonList(ChatMessage.builder(ChatRole.USER)
                                        .addContent(first.approve())
                                        .build()),
                                options)
                        .toCompletableFuture()
                        .join());
        assertInstanceOf(ToolInvocationException.class, incomplete.getCause());
        assertEquals(0, invocations.get());

        client.getResponse(
                        List.of(
                                ChatMessage.builder(ChatRole.USER)
                                        .addContent(first.approve())
                                        .build(),
                                ChatMessage.builder(ChatRole.USER)
                                        .addContent(second.approve())
                                        .build()),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(2, invocations.get());
    }

    @Test
    void bindsServerManagedApprovalToContinuation() throws Exception {
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage("call-1", "echo", "{}"))
                        .continuationToken("response-1")
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(approvalTool("echo", new AtomicInteger()))
                .build();
        ToolApprovalRequestContent request = (ToolApprovalRequestContent)
                client.getResponse(
                                Collections.singletonList(ChatMessage.user("start")),
                                options)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .getMessages()
                        .get(1)
                        .getContents()
                        .get(0);
        ChatMessage approval = ChatMessage.builder(ChatRole.USER)
                .addContent(request.approve())
                .build();

        CompletionException mismatch = assertThrows(
                CompletionException.class,
                () -> client.getResponse(
                                Collections.singletonList(approval),
                                options)
                        .toCompletableFuture()
                        .join());
        assertInstanceOf(ToolInvocationException.class, mismatch.getCause());

        client.getResponse(
                        Collections.singletonList(approval),
                        options.toBuilder()
                                .continuationToken("response-1")
                                .build())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void executesBufferedToolLoopAndReturnsAllGeneratedMessages() throws Exception {
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage("call-1", "echo", "{\"value\":\"hello\"}"))
                        .responseId("response-1")
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .responseId("response-2")
                        .continuationToken("next")
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo a value",
                        Collections.singletonMap("type", "object"),
                        arguments -> CompletableFuture.completedFuture(
                                String.valueOf(arguments.get("value")))))
                .build();

        ChatResponse response = client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(2, inner.requests.size());
        assertEquals(3, inner.requests.get(1).size());
        assertEquals(ChatRole.TOOL, inner.requests.get(1).get(2).getRole());
        FunctionResultContent toolResult = assertInstanceOf(
                FunctionResultContent.class,
                inner.requests.get(1).get(2).getContents().get(0));
        assertEquals("call-1", toolResult.getCallId());
        assertEquals("hello", toolResult.getResult());
        assertEquals(3, response.getMessages().size());
        assertEquals("done", response.getText());
        assertEquals("response-2", response.getResponseId());
        assertEquals("next", response.getContinuationToken());
    }

    @Test
    void bufferedServerManagedLoopSendsOnlyToolResults() throws Exception {
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage("call-1", "echo", "{}"))
                        .continuationToken("response-1")
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .continuationToken("response-2")
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture("ok")))
                .build();

        client.getResponse(Collections.singletonList(ChatMessage.user("start")), options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(1, inner.requests.get(1).size());
        assertEquals(ChatRole.TOOL, inner.requests.get(1).get(0).getRole());
        assertEquals("response-1", inner.optionsRequests.get(1).getContinuationToken());
    }

    @Test
    void invalidArgumentsFailWithoutCallingTool() {
        AtomicInteger invocations = new AtomicInteger();
        SequencedChatClient inner = new SequencedChatClient(ChatResponse.builder()
                .message(functionCallMessage("call-1", "echo", "{bad json"))
                .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> {
                            invocations.incrementAndGet();
                            return CompletableFuture.completedFuture("unexpected");
                        }))
                .build();

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> client.getResponse(
                                Collections.singletonList(ChatMessage.user("start")),
                                options)
                        .toCompletableFuture()
                        .join());

        assertInstanceOf(ToolInvocationException.class, error.getCause());
        assertEquals(0, invocations.get());
    }

    @Test
    void functionMiddlewareCanMutateArgumentsAndResult() throws Exception {
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage(
                                "call-1",
                                "echo",
                                "{\"value\":\"original\"}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        List<String> events = new ArrayList<>();
        FunctionMiddleware first = (context, next) -> {
            events.add("first-before");
            context.getMetadata().put("trace", "shared");
            context.setArguments(Collections.singletonMap("value", "changed"));
            return next.invoke(context).thenApply(result -> {
                events.add("first-after");
                return result.toUpperCase(java.util.Locale.ROOT);
            });
        };
        FunctionMiddleware second = (context, next) -> {
            events.add("second-before-" + context.getMetadata().get("trace"));
            return next.invoke(context).thenApply(result -> {
                events.add("second-after");
                return result;
            });
        };
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(
                inner,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                FunctionInvokingChatClient.DEFAULT_MAX_ITERATIONS,
                List.of(first, second));
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture(
                                String.valueOf(arguments.get("value")))))
                .build();

        ChatResponse response = client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        FunctionResultContent result = assertInstanceOf(
                FunctionResultContent.class,
                response.getMessages().get(1).getContents().get(0));
        assertEquals("CHANGED", result.getResult());
        assertEquals(
                List.of(
                        "first-before",
                        "second-before-shared",
                        "second-after",
                        "first-after"),
                events);
    }

    @Test
    void progressiveToolIsAdvertisedAndInvokedOnNextBufferedTurn()
            throws Exception {
        AtomicInteger dynamicInvocations = new AtomicInteger();
        FunctionTool dynamic = new FunctionTool(
                "dynamic",
                "Dynamic",
                Collections.emptyMap(),
                arguments -> {
                    dynamicInvocations.incrementAndGet();
                    return CompletableFuture.completedFuture("dynamic result");
                });
        FunctionTool bootstrap = new FunctionTool(
                "bootstrap",
                "Bootstrap",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ready"));
        FunctionMiddleware middleware = (context, next) -> {
            if ("bootstrap".equals(context.getTool().getName())) {
                context.addTools(dynamic);
            }
            return next.invoke(context);
        };
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage(
                                "call-1",
                                "bootstrap",
                                "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(functionCallMessage(
                                "call-2",
                                "dynamic",
                                "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(
                inner,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                8,
                Collections.singletonList(middleware));

        ChatResponse response = client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        ChatOptions.builder().tool(bootstrap).build())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("done", response.getText());
        assertEquals(1, dynamicInvocations.get());
        assertEquals(
                List.of("bootstrap", "dynamic"),
                toolNames(inner.optionsRequests.get(1)));
    }

    @Test
    void progressiveToolIsAdvertisedAndInvokedOnNextStreamingTurn()
            throws Exception {
        AtomicInteger dynamicInvocations = new AtomicInteger();
        FunctionTool dynamic = new FunctionTool(
                "dynamic",
                "Dynamic",
                Collections.emptyMap(),
                arguments -> {
                    dynamicInvocations.incrementAndGet();
                    return CompletableFuture.completedFuture("dynamic result");
                });
        FunctionTool bootstrap = new FunctionTool(
                "bootstrap",
                "Bootstrap",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ready"));
        FunctionMiddleware middleware = (context, next) -> {
            if ("bootstrap".equals(context.getTool().getName())) {
                context.addTools(dynamic);
            }
            return next.invoke(context);
        };
        SequencedStreamingChatClient inner =
                new SequencedStreamingChatClient(
                        ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .content(new FunctionCallContent(
                                        "call-1",
                                        "bootstrap",
                                        "{}"))
                                .finishReason(FinishReason.TOOL_CALLS)
                                .build(),
                        ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .content(new FunctionCallContent(
                                        "call-2",
                                        "dynamic",
                                        "{}"))
                                .finishReason(FinishReason.TOOL_CALLS)
                                .build(),
                        ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .text("done")
                                .finishReason(FinishReason.STOP)
                                .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(
                inner,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                8,
                Collections.singletonList(middleware));
        CollectingSubscriber subscriber = new CollectingSubscriber();

        client.getStreamingResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        ChatOptions.builder().tool(bootstrap).build())
                .subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.completion.get(5, TimeUnit.SECONDS);

        assertEquals(1, dynamicInvocations.get());
        assertEquals(
                List.of("bootstrap", "dynamic"),
                toolNames(inner.optionsRequests.get(1)));
    }

    @Test
    void progressiveToolsSurviveApprovalResume() throws Exception {
        AtomicInteger dynamicInvocations = new AtomicInteger();
        FunctionTool dynamic = new FunctionTool(
                "dynamic",
                "Dynamic",
                Collections.emptyMap(),
                arguments -> {
                    dynamicInvocations.incrementAndGet();
                    return CompletableFuture.completedFuture("approved");
                },
                ApprovalMode.ALWAYS_REQUIRE);
        FunctionTool bootstrap = new FunctionTool(
                "bootstrap",
                "Bootstrap",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ready"));
        FunctionMiddleware middleware = (context, next) -> {
            if ("bootstrap".equals(context.getTool().getName())) {
                context.addTools(dynamic);
            }
            return next.invoke(context);
        };
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage(
                                "call-1",
                                "bootstrap",
                                "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(functionCallMessage(
                                "call-2",
                                "dynamic",
                                "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(
                inner,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                8,
                Collections.singletonList(middleware));
        ChatOptions options = ChatOptions.builder().tool(bootstrap).build();

        ChatResponse paused = client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ToolApprovalRequestContent request = assertInstanceOf(
                ToolApprovalRequestContent.class,
                paused.getMessages().get(paused.getMessages().size() - 1)
                        .getContents().get(0));
        ChatResponse response = client.getResponse(
                        Collections.singletonList(ChatMessage.builder(ChatRole.USER)
                                .addContent(request.approve())
                                .build()),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("done", response.getText());
        assertEquals(1, dynamicInvocations.get());
        assertEquals(
                List.of("bootstrap", "dynamic"),
                toolNames(inner.optionsRequests.get(2)));
    }

    @Test
    void approvalResumesThroughSharedStoreAfterClientRestart()
            throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool("echo", invocations);
        InMemoryToolApprovalStore store = new InMemoryToolApprovalStore();
        SequencedChatClient firstInner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage(
                                "call-1",
                                "echo",
                                "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build());
        FunctionInvokingChatClient first =
                new FunctionInvokingChatClient(
                        firstInner,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        8,
                        Collections.emptyList(),
                        store,
                        Duration.ofMinutes(1));
        ChatOptions options = ChatOptions.builder().tool(tool).build();
        ChatResponse paused = first.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ToolApprovalRequestContent request = assertInstanceOf(
                ToolApprovalRequestContent.class,
                paused.getMessages().get(1).getContents().get(0));
        SequencedChatClient secondInner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient second =
                new FunctionInvokingChatClient(
                        secondInner,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        8,
                        Collections.emptyList(),
                        store,
                        Duration.ofMinutes(1));

        ChatResponse completed = second.getResponse(
                        Collections.singletonList(ChatMessage.builder(ChatRole.USER)
                                .addContent(request.approve())
                                .build()),
                        options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("done", completed.getText());
        assertEquals(1, invocations.get());
    }

    @Test
    void progressiveToolsRequireLiveLoop() {
        FunctionTool original = new FunctionTool(
                "same",
                "Original",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ok"));
        FunctionInvocationContext detached = new FunctionInvocationContext(
                original,
                new FunctionCallContent("call-1", "same", "{}"),
                Collections.emptyMap(),
                ChatOptions.builder().tool(original).build());

        assertThrows(IllegalStateException.class, detached::getTools);
        assertThrows(
                IllegalStateException.class,
                () -> detached.addTools(original));
        assertThrows(
                IllegalStateException.class,
                () -> detached.removeTools("same"));
    }

    @Test
    void progressiveToolBatchMutationIsAtomic() throws Exception {
        AtomicBoolean rejected = new AtomicBoolean();
        FunctionTool bootstrap = new FunctionTool(
                "bootstrap",
                "Bootstrap",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ok"));
        FunctionTool existing = new FunctionTool(
                "same",
                "Existing",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ok"));
        FunctionTool replacement = new FunctionTool(
                "same",
                "Replacement",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ok"));
        FunctionTool extra = new FunctionTool(
                "extra",
                "Extra",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ok"));
        FunctionMiddleware middleware = (context, next) -> {
            try {
                context.addTools(List.of(replacement, extra));
            } catch (IllegalArgumentException expected) {
                rejected.set(true);
            }
            assertEquals(
                    List.of("bootstrap", "same"),
                    context.getTools().stream()
                            .map(tool -> tool.getName())
                            .collect(java.util.stream.Collectors.toList()));
            return next.invoke(context);
        };
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(functionCallMessage(
                                "call-1",
                                "bootstrap",
                                "{}"))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(
                inner,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                8,
                Collections.singletonList(middleware));

        client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        ChatOptions.builder()
                                .tools(List.of(bootstrap, existing))
                                .build())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertTrue(rejected.get());
        assertEquals(
                List.of("bootstrap", "same"),
                toolNames(inner.optionsRequests.get(1)));
    }

    @Test
    void progressiveRemovalDoesNotChangeInflightToolBatch() throws Exception {
        AtomicInteger targetInvocations = new AtomicInteger();
        FunctionTool remover = new FunctionTool(
                "remover",
                "Remover",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("removed"));
        FunctionTool target = new FunctionTool(
                "target",
                "Target",
                Collections.emptyMap(),
                arguments -> {
                    targetInvocations.incrementAndGet();
                    return CompletableFuture.completedFuture("target result");
                });
        FunctionMiddleware middleware = (context, next) -> {
            if ("remover".equals(context.getTool().getName())) {
                context.removeTools("target", "unknown");
            }
            return next.invoke(context);
        };
        SequencedChatClient inner = new SequencedChatClient(
                ChatResponse.builder()
                        .message(ChatMessage.builder(ChatRole.ASSISTANT)
                                .addContent(new FunctionCallContent(
                                        "call-1",
                                        "remover",
                                        "{}"))
                                .addContent(new FunctionCallContent(
                                        "call-2",
                                        "target",
                                        "{}"))
                                .build())
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .message(ChatMessage.assistant("done"))
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(
                inner,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                8,
                Collections.singletonList(middleware));

        client.getResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        ChatOptions.builder()
                                .tools(List.of(remover, target))
                                .build())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(1, targetInvocations.get());
        assertEquals(
                Collections.singletonList("remover"),
                toolNames(inner.optionsRequests.get(1)));
    }

    @Test
    void executesStreamingToolLoopAcrossPublishers() throws Exception {
        FunctionCallContent call =
                new FunctionCallContent("call-1", "echo", "{\"value\":\"streamed\"}");
        SequencedStreamingChatClient inner = new SequencedStreamingChatClient(
                ChatResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(call)
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .text("done")
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture(
                                String.valueOf(arguments.get("value")))))
                .build();
        CollectingSubscriber subscriber = new CollectingSubscriber();

        client.getStreamingResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.completion.get(5, TimeUnit.SECONDS);

        assertEquals(2, subscriber.updates.size());
        assertEquals(call, subscriber.updates.get(0).getContents().get(0));
        assertEquals("done", subscriber.updates.get(1).getText());
        assertEquals(2, inner.requests.size());
        assertEquals(ChatRole.TOOL, inner.requests.get(1).get(2).getRole());
    }

    @Test
    void streamingApprovalRequestHonorsDemand() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        FunctionCallContent call =
                new FunctionCallContent("call-1", "echo", "{}");
        SequencedStreamingChatClient inner = new SequencedStreamingChatClient(
                ChatResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(call)
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> {
                            invocations.incrementAndGet();
                            return CompletableFuture.completedFuture("ok");
                        },
                        ApprovalMode.ALWAYS_REQUIRE))
                .build();
        CollectingSubscriber subscriber = new CollectingSubscriber();

        client.getStreamingResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .subscribe(subscriber);
        subscriber.subscription.request(1);

        assertEquals(1, subscriber.updates.size());
        assertEquals(call, subscriber.updates.get(0).getContents().get(0));
        assertTrue(!subscriber.completion.isDone());

        subscriber.subscription.request(1);
        subscriber.completion.get(5, TimeUnit.SECONDS);

        assertEquals(2, subscriber.updates.size());
        assertInstanceOf(
                ToolApprovalRequestContent.class,
                subscriber.updates.get(1).getContents().get(0));
        assertEquals(0, invocations.get());
    }

    @Test
    void streamingServerManagedLoopSendsOnlyToolResults() throws Exception {
        SequencedStreamingChatClient inner = new SequencedStreamingChatClient(
                ChatResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(new FunctionCallContent("call-1", "echo", "{}"))
                        .continuationToken("response-1")
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .text("done")
                        .continuationToken("response-2")
                        .finishReason(FinishReason.STOP)
                        .build());
        FunctionInvokingChatClient client = new FunctionInvokingChatClient(inner);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture("ok")))
                .build();
        CollectingSubscriber subscriber = new CollectingSubscriber();

        client.getStreamingResponse(
                        Collections.singletonList(ChatMessage.user("start")),
                        options)
                .subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.completion.get(5, TimeUnit.SECONDS);

        assertEquals(1, inner.requests.get(1).size());
        assertEquals(ChatRole.TOOL, inner.requests.get(1).get(0).getRole());
        assertEquals("response-1", inner.optionsRequests.get(1).getContinuationToken());
    }

    @Test
    void stopsRunawayToolLoops() {
        ChatResponse repeating = ChatResponse.builder()
                .message(functionCallMessage("call-1", "echo", "{}"))
                .build();
        RepeatingChatClient inner = new RepeatingChatClient(repeating);
        FunctionInvokingChatClient client =
                new FunctionInvokingChatClient(inner, new com.fasterxml.jackson.databind.ObjectMapper(), 2);
        ChatOptions options = ChatOptions.builder()
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture("ok")))
                .build();

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> client.getResponse(
                                Collections.singletonList(ChatMessage.user("start")),
                                options)
                        .toCompletableFuture()
                        .join());

        assertInstanceOf(ToolInvocationException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("2 iterations"));
        assertEquals(3, inner.calls.get());
    }

    private static ChatMessage functionCallMessage(
            String callId,
            String name,
            String arguments) {
        return ChatMessage.builder(ChatRole.ASSISTANT)
                .addContent(new FunctionCallContent(callId, name, arguments))
                .build();
    }

    private static FunctionTool approvalTool(
            String name,
            AtomicInteger invocations) {
        return new FunctionTool(
                name,
                name,
                Collections.emptyMap(),
                arguments -> {
                    invocations.incrementAndGet();
                    return CompletableFuture.completedFuture("ok");
                },
                ApprovalMode.ALWAYS_REQUIRE);
    }

    private static List<String> toolNames(ChatOptions options) {
        List<String> names = new ArrayList<>();
        options.getTools().forEach(tool -> names.add(tool.getName()));
        return names;
    }

    private static final class SequencedChatClient implements ChatClient {
        private final List<ChatResponse> responses;
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final List<ChatOptions> optionsRequests = new ArrayList<>();
        private int index;

        private SequencedChatClient(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            requests.add(new ArrayList<>(messages));
                optionsRequests.add(options);
                return CompletableFuture.completedFuture(responses.get(index++));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RepeatingChatClient implements ChatClient {
        private final ChatResponse response;
        private final AtomicInteger calls = new AtomicInteger();

        private RepeatingChatClient(ChatResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SequencedStreamingChatClient implements ChatClient {
        private final List<ChatResponseUpdate> responses;
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final List<ChatOptions> optionsRequests = new ArrayList<>();
        private int index;

        private SequencedStreamingChatClient(ChatResponseUpdate... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            requests.add(new ArrayList<>(messages));
                optionsRequests.add(options);
                ChatResponseUpdate response = responses.get(index++);
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done && count > 0) {
                        done = true;
                        subscriber.onNext(response);
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

    private static final class CollectingSubscriber
            implements Flow.Subscriber<ChatResponseUpdate> {
        private final List<ChatResponseUpdate> updates = new ArrayList<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
            updates.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completion.complete(null);
        }
    }
}
