package io.github.weidongxu.agentframework.foundry;

import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoundryHostedContextTest {
    @Test
    void preservesHostedProtocolIdentifiers() {
        AgentRunOptions runOptions =
                FoundryHostedContext.runOptions("user-1", "call-1");
        ChatOptions chatOptions = ChatOptions.builder()
                .additionalProperty(
                        FoundryHostedContext.USER_ID_HEADER,
                        runOptions.getAdditionalProperties().get(
                                FoundryHostedContext.USER_ID_HEADER))
                .additionalProperty(
                        FoundryHostedContext.CALL_ID_HEADER,
                        runOptions.getAdditionalProperties().get(
                                FoundryHostedContext.CALL_ID_HEADER))
                .build();

        assertEquals(
                "user-1",
                FoundryHostedContext.getUserId(chatOptions));
        assertEquals(
                "call-1",
                FoundryHostedContext.getCallId(chatOptions));
    }
}
