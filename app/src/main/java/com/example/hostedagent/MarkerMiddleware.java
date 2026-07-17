package com.example.hostedagent;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.middleware.AgentMiddleware;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareNext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

/**
 * Demonstrates the framework {@link AgentMiddleware} extension point end-to-end (the deck's
 * "middleware" snippet). It wraps every run: it inspects the request messages and, only when a
 * caller opts in with the {@code MW_PING} sentinel, mutates the request by appending a system
 * instruction that makes the model end its reply with the exact token {@code [[mw-ok]]}. A client
 * can then assert that token to prove the middleware actually executed in-container.
 *
 * <p>Normal traffic (no sentinel) is passed through unchanged, so the middleware is inert unless a
 * verification turn explicitly triggers it.
 */
final class MarkerMiddleware implements AgentMiddleware {

    static final String SENTINEL = "MW_PING";
    static final String MARKER = "[[mw-ok]]";

    @Override
    public CompletionStage<AgentResponse> invoke(
            AgentMiddlewareContext context, AgentMiddlewareNext next) {
        boolean triggered = context.getMessages().stream()
                .map(ChatMessage::getText)
                .filter(text -> text != null)
                .anyMatch(text -> text.toUpperCase(Locale.ROOT).contains(SENTINEL));
        if (triggered) {
            List<ChatMessage> messages = new ArrayList<>(context.getMessages());
            messages.add(ChatMessage.system(
                    "You are running behind verification middleware. Because the user's message "
                            + "contains the sentinel " + SENTINEL + ", you MUST end your reply with "
                            + "the exact token " + MARKER + " on its own final line."));
            context.setMessages(messages);
        }
        return next.invoke(context);
    }
}
