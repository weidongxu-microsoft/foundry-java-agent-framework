package io.github.weidongxu.agentframework.observability.opentelemetry;

import java.util.Objects;

public final class OpenTelemetryOptions {
    public static final String DEFAULT_INSTRUMENTATION_NAME =
            "io.github.weidongxu.agentframework";

    private final String instrumentationName;
    private final boolean captureSensitiveData;

    private OpenTelemetryOptions(Builder builder) {
        this.instrumentationName = builder.instrumentationName;
        this.captureSensitiveData = builder.captureSensitiveData;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OpenTelemetryOptions defaults() {
        return builder().build();
    }

    public String getInstrumentationName() {
        return instrumentationName;
    }

    public boolean isCaptureSensitiveData() {
        return captureSensitiveData;
    }

    public static final class Builder {
        private String instrumentationName = DEFAULT_INSTRUMENTATION_NAME;
        private boolean captureSensitiveData;

        private Builder() {
        }

        public Builder instrumentationName(String instrumentationName) {
            Objects.requireNonNull(instrumentationName, "instrumentationName");
            if (instrumentationName.isBlank()) {
                throw new IllegalArgumentException(
                        "instrumentationName cannot be blank");
            }
            this.instrumentationName = instrumentationName;
            return this;
        }

        public Builder captureSensitiveData(boolean captureSensitiveData) {
            this.captureSensitiveData = captureSensitiveData;
            return this;
        }

        public OpenTelemetryOptions build() {
            return new OpenTelemetryOptions(this);
        }
    }
}
