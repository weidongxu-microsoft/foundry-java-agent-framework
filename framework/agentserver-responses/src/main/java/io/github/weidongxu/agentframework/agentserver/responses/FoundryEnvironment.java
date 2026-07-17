package io.github.weidongxu.agentframework.agentserver.responses;

import java.time.Duration;

/**
 * Strongly-typed access to the Foundry platform environment variables injected by the Azure AI
 * Foundry hosting infrastructure. Java counterpart of {@code Azure.AI.AgentServer.Core.FoundryEnvironment}.
 *
 * <p>Values are read once and cached. {@link #reload()} re-reads them and is intended for tests only.</p>
 */
public final class FoundryEnvironment {

    private FoundryEnvironment() {
    }

    private static volatile String agentName;
    private static volatile String agentId;
    private static volatile String agentVersion;
    private static volatile String projectEndpoint;
    private static volatile String projectArmId;
    private static volatile String sessionId;
    private static volatile int port;
    private static volatile String otlpEndpoint;
    private static volatile String appInsightsConnectionString;
    private static volatile Duration sseKeepAliveInterval;
    private static volatile boolean hosted;

    static {
        reload();
    }

    /** {@code FOUNDRY_AGENT_NAME}. */
    public static String agentName() {
        return agentName;
    }

    /** {@code FOUNDRY_AGENT_ID} — the agent's stable GUID. */
    public static String agentId() {
        return agentId;
    }

    /** {@code FOUNDRY_AGENT_VERSION}. */
    public static String agentVersion() {
        return agentVersion;
    }

    /** {@code FOUNDRY_PROJECT_ENDPOINT}. */
    public static String projectEndpoint() {
        return projectEndpoint;
    }

    /** {@code FOUNDRY_PROJECT_ARM_ID}. */
    public static String projectArmId() {
        return projectArmId;
    }

    /** {@code FOUNDRY_AGENT_SESSION_ID}. */
    public static String sessionId() {
        return sessionId;
    }

    /** {@code PORT} — the HTTP listen port. Default {@code 8088}. */
    public static int port() {
        return port;
    }

    /** {@code OTEL_EXPORTER_OTLP_ENDPOINT}. */
    public static String otlpEndpoint() {
        return otlpEndpoint;
    }

    /** {@code APPLICATIONINSIGHTS_CONNECTION_STRING}. */
    public static String appInsightsConnectionString() {
        return appInsightsConnectionString;
    }

    /**
     * {@code SSE_KEEPALIVE_INTERVAL} (integer seconds) — the SSE keep-alive comment-frame interval.
     * {@link Duration#ZERO} when absent, zero, or unparseable (disabled).
     */
    public static Duration sseKeepAliveInterval() {
        return sseKeepAliveInterval;
    }

    /**
     * Whether the process is running in a Foundry hosted environment — {@code true} when
     * {@code FOUNDRY_HOSTING_ENVIRONMENT} is set to a non-empty value.
     */
    public static boolean isHosted() {
        return hosted;
    }

    /** Re-reads all environment variables. For test isolation only. */
    public static void reload() {
        agentName = env("FOUNDRY_AGENT_NAME");
        agentId = env("FOUNDRY_AGENT_ID");
        agentVersion = env("FOUNDRY_AGENT_VERSION");
        projectEndpoint = env("FOUNDRY_PROJECT_ENDPOINT");
        projectArmId = env("FOUNDRY_PROJECT_ARM_ID");
        sessionId = env("FOUNDRY_AGENT_SESSION_ID");
        otlpEndpoint = env("OTEL_EXPORTER_OTLP_ENDPOINT");
        appInsightsConnectionString = env("APPLICATIONINSIGHTS_CONNECTION_STRING");

        String portEnv = env("PORT");
        if (portEnv == null) {
            port = 8088;
        } else {
            int parsed;
            try {
                parsed = Integer.parseInt(portEnv);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException(
                        "The PORT environment variable value '" + portEnv + "' is not a valid port number (1-65535).");
            }
            if (parsed < 1 || parsed > 65535) {
                throw new IllegalStateException(
                        "The PORT environment variable value '" + portEnv + "' is not a valid port number (1-65535).");
            }
            port = parsed;
        }

        String sseEnv = env("SSE_KEEPALIVE_INTERVAL");
        long seconds = 0;
        if (sseEnv != null) {
            try {
                seconds = Long.parseLong(sseEnv);
            } catch (NumberFormatException ignored) {
                seconds = 0;
            }
        }
        sseKeepAliveInterval = seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO;

        hosted = env("FOUNDRY_HOSTING_ENVIRONMENT") != null;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
