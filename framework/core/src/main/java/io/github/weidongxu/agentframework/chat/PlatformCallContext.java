package io.github.weidongxu.agentframework.chat;

import java.util.function.Supplier;

/**
 * Thread-scoped holder for the platform-minted {@code x-agent-foundry-call-id} of the request being
 * served, used to forward that id on outbound calls whose client adapter has no direct access to the
 * run's {@link ChatOptions} — notably the synchronous MCP tool client. A caller binds the id on the
 * thread that makes the synchronous outbound call; the adapter reads {@link #current()} at send time
 * and stamps the header.
 *
 * <p>This lives in {@code core} (rather than the Foundry module) so lower adapters like the MCP
 * client can read it without depending on Foundry. It carries no cross-thread propagation — bind and
 * read must occur on the same thread.</p>
 */
public final class PlatformCallContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private PlatformCallContext() {
    }

    /** The call id bound to the current thread, or {@code null} when none is set. */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Runs {@code action} with {@code callId} bound to the current thread, restoring the prior
     * binding afterwards. A {@code null}/blank {@code callId} runs {@code action} unchanged.
     */
    public static <T> T callWith(String callId, Supplier<T> action) {
        if (callId == null || callId.trim().isEmpty()) {
            return action.get();
        }
        String previous = CURRENT.get();
        CURRENT.set(callId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@link #callWith(String, Supplier)} for actions with no return value. */
    public static void runWith(String callId, Runnable action) {
        callWith(callId, () -> {
            action.run();
            return null;
        });
    }
}
