package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Python 或 Java 为某次 Agent Run 追加的版本化生命周期事件。
 */
@Data
@NoArgsConstructor
public class AgentRunEvent {
    private String eventId;
    private int schemaVersion;
    private AgentRunEventType eventType;
    private long sequence;
    private Instant occurredAt;
    private String conversationId;
    private String runId;
    private String message;
    private AgentRunEventSource source;

    @JsonCreator
    public AgentRunEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("eventType") AgentRunEventType eventType,
            @JsonProperty("sequence") long sequence,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("conversationId") String conversationId,
            @JsonProperty("runId") String runId,
            @JsonProperty("message") String message,
            @JsonProperty("source") AgentRunEventSource source) {
        requireText(eventId, "eventId");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType is not supported");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        requireText(conversationId, "conversationId");
        requireText(runId, "runId");
        requireText(message, "message");
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (message.length() > 4000) {
            throw new IllegalArgumentException("message must be at most 4000 characters");
        }
        this.eventId = eventId;
        this.schemaVersion = schemaVersion;
        this.eventType = eventType;
        this.sequence = sequence;
        this.occurredAt = occurredAt;
        this.conversationId = conversationId;
        this.runId = runId;
        this.message = message;
        this.source = source;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
