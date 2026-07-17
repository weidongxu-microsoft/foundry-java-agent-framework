package io.github.weidongxu.agentframework.compaction;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionProviderTest {

    private static final Agent STUB_AGENT = new Agent() {
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

    private static AIContext compact(CompactionProvider provider, AIContext input) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB_AGENT, new AgentSession(), input))
                .toCompletableFuture().get();
    }

    private static ChatMessage msg(ChatRole role, String text) {
        return ChatMessage.builder(role).text(text).build();
    }

    private static AIContext turns(int userTurns) {
        AIContext.Builder builder = AIContext.builder().instructions("sys");
        for (int i = 1; i <= userTurns; i++) {
            builder.message(msg(ChatRole.USER, "u" + i));
            builder.message(msg(ChatRole.ASSISTANT, "a" + i));
        }
        return builder.build();
    }

    private static List<String> texts(AIContext context) {
        return context.getMessages().stream().map(ChatMessage::getText).collect(Collectors.toList());
    }

    @Test
    void belowThresholdReturnsSameContextUntouched() throws Exception {
        AIContext input = turns(3);
        AIContext result = compact(new CompactionProvider(new TruncationCompactionStrategy(32, 32)), input);
        assertSame(input, result);
    }

    @Test
    void truncationKeepsMostRecentTurnsAndDropsOldest() throws Exception {
        AIContext input = turns(5);
        AIContext result = compact(new CompactionProvider(new TruncationCompactionStrategy(2, 2)), input);
        assertEquals(List.of("u4", "a4", "u5", "a5"), texts(result));
        assertEquals("sys", result.getInstructions());
    }

    @Test
    void slidingWindowKeepsLastTurnOnly() throws Exception {
        AIContext input = turns(4);
        AIContext result = compact(CompactionProvider.slidingWindow(1), input);
        assertEquals(List.of("u4", "a4"), texts(result));
    }

    @Test
    void systemMessagesAreNeverExcluded() throws Exception {
        AIContext.Builder builder = AIContext.builder();
        builder.message(msg(ChatRole.SYSTEM, "S"));
        for (int i = 1; i <= 4; i++) {
            builder.message(msg(ChatRole.USER, "u" + i));
            builder.message(msg(ChatRole.ASSISTANT, "a" + i));
        }
        AIContext result = compact(CompactionProvider.slidingWindow(1), builder.build());
        assertTrue(texts(result).contains("S"));
        assertEquals(List.of("S", "u4", "a4"), texts(result));
    }

    @Test
    void toolResultStaysWithItsUserTurn() throws Exception {
        AIContext input = AIContext.builder()
                .message(msg(ChatRole.USER, "u1"))
                .message(msg(ChatRole.ASSISTANT, "a1-call"))
                .message(msg(ChatRole.TOOL, "t1-result"))
                .message(msg(ChatRole.USER, "u2"))
                .message(msg(ChatRole.ASSISTANT, "a2"))
                .build();
        AIContext result = compact(CompactionProvider.slidingWindow(1), input);
        assertEquals(List.of("u2", "a2"), texts(result));
    }

    @Test
    void keepingAllTurnsPreservesToolPairs() throws Exception {
        List<ChatMessage> all = new ArrayList<>();
        all.add(msg(ChatRole.USER, "u1"));
        all.add(msg(ChatRole.ASSISTANT, "a1"));
        all.add(msg(ChatRole.TOOL, "t1"));
        AIContext input = AIContext.builder().messages(all).build();
        AIContext result = compact(new CompactionProvider(new TruncationCompactionStrategy(32, 32)), input);
        assertSame(input, result);
    }
}
