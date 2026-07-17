package io.github.weidongxu.agentframework.foundry;

import io.github.weidongxu.agentframework.chat.PlatformCallContext;

import java.util.function.Supplier;

/**
 * Thread-scoped holder for the platform-minted {@code x-agent-foundry-call-id} of the request
 * currently being served. {@link FoundryClientFactory} installs {@link FoundryCallIdPolicy} on every
 * outbound Foundry client; that policy reads {@link #current()} at HTTP-send time and forwards the
 * id verbatim, satisfying the platform contract that the container propagate the call id on outbound
 * calls to Foundry platform services (Storage / Toolboxes-MCP / A2A).
 *
 * <p>The value is set on the same thread that makes the synchronous SDK call (see
 * {@link SdkFoundryMemoryClient}), so it is visible to the sync HTTP pipeline without cross-thread
 * propagation.</p>
 *
 * <p>Delegates to the framework-wide {@link PlatformCallContext} so the Foundry Storage path and the
 * lower MCP client share a single per-thread call-id binding.</p>
 */
public final class FoundryCallContext {

    private FoundryCallContext() {
    }

    /** The call id bound to the current thread, or {@code null} when none is set. */
    public static String current() {
        return PlatformCallContext.current();
    }

    /**
     * Run {@code action} with {@code callId} bound to the current thread, restoring the prior binding
     * afterwards. A {@code null}/blank {@code callId} runs {@code action} unchanged.
     */
    public static <T> T callWith(String callId, Supplier<T> action) {
        return PlatformCallContext.callWith(callId, action);
    }

    /** {@link #callWith(String, Supplier)} for actions with no return value. */
    public static void runWith(String callId, Runnable action) {
        PlatformCallContext.runWith(callId, action);
    }
}

