package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Java 发布给 Python Agent Runtime 的版本化 Agent Run 请求事件。
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRunRequest {
    private String eventId;
    private int schemaVersion;
    private AgentRunEventType eventType;
    private long sequence;
    private Instant occurredAt;
    private String conversationId;
    private String runId;
    private String message;
    private String taskGoal;
    private Long lastEventSequence;
    private String metricDefinitionVersionId;
    private String queryGrant;

    @JsonCreator
    public AgentRunRequest(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("eventType") AgentRunEventType eventType,
            @JsonProperty("sequence") long sequence,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("conversationId") String conversationId,
            @JsonProperty("runId") String runId,
            @JsonProperty("message") String message) {
        this(eventId, schemaVersion, eventType, sequence, occurredAt,
                conversationId, runId, message, null, null, null, null);
    }

    public AgentRunRequest(
            String eventId,
            int schemaVersion,
            AgentRunEventType eventType,
            long sequence,
            Instant occurredAt,
            String conversationId,
            String runId,
            String message,
            String taskGoal,
            Long lastEventSequence,
            String metricDefinitionVersionId,
            String queryGrant) {
        requireText(eventId, "eventId");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (eventType != AgentRunEventType.REQUESTED && eventType != AgentRunEventType.CANCEL_REQUESTED) {
            throw new IllegalArgumentException("eventType must request starting or cancelling an Agent Run");
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
        if (message.length() > 4000) {
            throw new IllegalArgumentException("message must be at most 4000 characters");
        }
        if (taskGoal != null && (taskGoal.isBlank() || taskGoal.length() > 4000)) {
            throw new IllegalArgumentException("taskGoal must be 1-4000 characters");
        }
        if (lastEventSequence != null && lastEventSequence < 0) {
            throw new IllegalArgumentException("lastEventSequence must not be negative");
        }
        if (metricDefinitionVersionId != null && metricDefinitionVersionId.isBlank()) {
            throw new IllegalArgumentException("metricDefinitionVersionId must not be blank");
        }
        if (queryGrant != null && queryGrant.isBlank()) {
            throw new IllegalArgumentException("queryGrant must not be blank");
        }
        this.eventId = eventId;
        this.schemaVersion = schemaVersion;
        this.eventType = eventType;
        this.sequence = sequence;
        this.occurredAt = occurredAt;
        this.conversationId = conversationId;
        this.runId = runId;
        this.message = message;
        this.taskGoal = taskGoal;
        this.lastEventSequence = lastEventSequence;
        this.metricDefinitionVersionId = metricDefinitionVersionId;
        this.queryGrant = queryGrant;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
