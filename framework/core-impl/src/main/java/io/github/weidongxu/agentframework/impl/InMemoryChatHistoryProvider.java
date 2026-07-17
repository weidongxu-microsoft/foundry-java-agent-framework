package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agent.ChatHistoryProvider;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class InMemoryChatHistoryProvider extends ChatHistoryProvider {
    public static final String DEFAULT_STATE_KEY =
            InMemoryChatHistoryProvider.class.getName();

    private final String stateKey;

    public InMemoryChatHistoryProvider() {
        this(DEFAULT_STATE_KEY);
    }

    public InMemoryChatHistoryProvider(String stateKey) {
        this.stateKey = Objects.requireNonNull(stateKey, "stateKey");
    }

    @Override
    public List<String> getStateKeys() {
        return Collections.singletonList(stateKey);
    }

    public List<ChatMessage> getMessages(AgentSession session) {
        if (session == null) {
            return Collections.emptyList();
        }
        synchronized (session) {
            Object stored = session.get(stateKey);
            if (stored == null) {
                return Collections.emptyList();
            }
            if (!(stored instanceof List<?>)) {
                throw new IllegalStateException(
                        "History state is not a message list: " + stateKey);
            }
            List<ChatMessage> messages = new ArrayList<>();
            for (Object item : (List<?>) stored) {
                if (!(item instanceof ChatMessage)) {
                    throw new IllegalStateException(
                            "History state contains a non-message value: " + stateKey);
                }
                messages.add((ChatMessage) item);
            }
            return Collections.unmodifiableList(messages);
        }
    }

    public void setMessages(AgentSession session, List<? extends ChatMessage> messages) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(messages, "messages");
        synchronized (session) {
            session.put(stateKey, new ArrayList<>(messages));
        }
    }

    @Override
    protected CompletionStage<List<ChatMessage>> provide(AgentInvokingContext context) {
        return CompletableFuture.completedFuture(getMessages(context.getSession()));
    }

    @Override
    protected CompletionStage<Void> store(AgentInvokedContext context) {
        if (context.getSession() != null) {
            synchronized (context.getSession()) {
                List<ChatMessage> messages =
                        new ArrayList<>(getMessages(context.getSession()));
                messages.addAll(context.getRequestMessages());
                messages.addAll(context.getResponseMessages());
                context.getSession().put(stateKey, messages);
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
