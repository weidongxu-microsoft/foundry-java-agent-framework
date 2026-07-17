package io.github.weidongxu.agentframework.workflow;

import java.time.Instant;
import java.util.Objects;

public final class WorkflowEvent {
    private final String type;
    private final String participant;
    private final Instant timestamp;

    public WorkflowEvent(
            String type,
            String participant,
            Instant timestamp) {
        this.type = requireNonBlank(type, "type");
        this.participant = participant;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public String getType() {
        return type;
    }

    public String getParticipant() {
        return participant;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
