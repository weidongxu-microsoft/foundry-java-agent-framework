package io.github.weidongxu.agentframework.agentserver.responses;

import java.util.function.Function;

/**
 * Carries the platform-injected identity for a single request. Java counterpart of
 * {@code Azure.AI.AgentServer.Core.PlatformContext}.
 *
 * <p>The Foundry platform sets {@link PlatformHeaders#USER_ID} and (on container protocol
 * {@code 2.0.0}) {@link PlatformHeaders#FOUNDRY_CALL_ID} on every protocol request. Both values are
 * opaque and platform-generated, and neither is guaranteed present when running locally.</p>
 *
 * <ul>
 *   <li><b>userId</b> — the global, cross-agent partition key for per-user state. Used container-side
 *       to scope per-user data; it is <b>not</b> forwarded on outbound first-party calls.</li>
 *   <li><b>callId</b> — the opaque per-request call id. The container <b>must</b> forward it verbatim
 *       on outbound calls to Foundry platform services; it is never parsed.</li>
 * </ul>
 */
public final class PlatformContext {

    /** An empty context (both values {@code null}) — used when the platform headers are absent. */
    public static final PlatformContext EMPTY = new PlatformContext(null, null);

    private final String userId;
    private final String callId;

    public PlatformContext(String userId, String callId) {
        this.userId = normalize(userId);
        this.callId = normalize(callId);
    }

    /**
     * Builds a context from a header lookup (e.g. {@code request::getHeader}). Honors
     * {@link PlatformHeaders#USER_ID} and falls back to the protocol {@code 1.0.0}
     * {@link PlatformHeaders#USER_ISOLATION_KEY} for the user id. Returns {@link #EMPTY} when neither
     * a user id nor a call id is present.
     */
    public static PlatformContext fromHeaders(Function<String, String> headerLookup) {
        if (headerLookup == null) {
            return EMPTY;
        }
        String userId = normalize(headerLookup.apply(PlatformHeaders.USER_ID));
        if (userId == null) {
            userId = normalize(headerLookup.apply(PlatformHeaders.USER_ISOLATION_KEY));
        }
        String callId = normalize(headerLookup.apply(PlatformHeaders.FOUNDRY_CALL_ID));
        if (userId == null && callId == null) {
            return EMPTY;
        }
        return new PlatformContext(userId, callId);
    }

    /** The {@code x-agent-user-id} value (or {@code null}) — the per-user state partition key. */
    public String userId() {
        return userId;
    }

    /** The {@code x-agent-foundry-call-id} value (or {@code null}) — forward verbatim, never parse. */
    public String callId() {
        return callId;
    }

    public boolean isEmpty() {
        return userId == null && callId == null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
