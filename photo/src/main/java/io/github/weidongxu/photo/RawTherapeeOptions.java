package io.github.weidongxu.photo;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for {@link RawTherapeeDeveloper}: how to locate the {@code rawtherapee-cli} binary,
 * the JPEG quality, and the per-develop timeout.
 */
public final class RawTherapeeOptions {

    /** Environment variable naming the {@code rawtherapee-cli} executable (path or command). */
    public static final String CLI_ENV = "RAWTHERAPEE_CLI";

    private static final String DEFAULT_CLI = "rawtherapee-cli";

    private final String cliPath;
    private final int jpegQuality;
    private final Duration timeout;

    private RawTherapeeOptions(Builder builder) {
        this.cliPath = builder.cliPath;
        this.jpegQuality = builder.jpegQuality;
        this.timeout = builder.timeout;
    }

    /** Defaults: resolve the CLI from {@value #CLI_ENV} or PATH, quality 92, 120s timeout. */
    public static RawTherapeeOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The resolved CLI command: the explicit {@link Builder#cliPath(String)} if set, else
     * {@value #CLI_ENV} from the environment, else {@value #DEFAULT_CLI} (found on PATH).
     */
    public String resolveCli() {
        if (cliPath != null && !cliPath.isBlank()) {
            return cliPath;
        }
        String fromEnv = System.getenv(CLI_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return DEFAULT_CLI;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public static final class Builder {
        private String cliPath;
        private int jpegQuality = 92;
        private Duration timeout = Duration.ofSeconds(120);

        public Builder cliPath(String cliPath) {
            this.cliPath = cliPath;
            return this;
        }

        public Builder jpegQuality(int jpegQuality) {
            if (jpegQuality < 1 || jpegQuality > 100) {
                throw new IllegalArgumentException("jpegQuality must be 1..100");
            }
            this.jpegQuality = jpegQuality;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public RawTherapeeOptions build() {
            return new RawTherapeeOptions(this);
        }
    }
}
