package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunContractValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentRunContractValidator validator = new AgentRunContractValidator(objectMapper);

    @Test
    void acceptsAnEventThatMatchesTheVersionedSchema() {
        validator.validateEvent(new AgentRunEvent(
                "evt-1", 1, AgentRunEventType.COMPLETED, 3,
                Instant.parse("2026-08-28T02:00:00Z"), "conv-1", "run-1",
                "done", AgentRunEventSource.PYTHON));
    }

    @Test
    void acceptsACancellationCommandThatMatchesTheVersionedSchema() {
        validator.validateRequest(new AgentRunRequest(
                "cancel-1", 1, AgentRunEventType.CANCEL_REQUESTED, 3,
                Instant.parse("2026-09-06T02:00:00Z"), "conv-1", "run-1",
                "用户主动停止分析"));
    }

    @Test
    void rejectsUnknownEventProperties() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "eventId":"evt-1",
                  "schemaVersion":1,
                  "eventType":"agent.run.completed",
                  "sequence":3,
                  "occurredAt":"2026-08-28T02:00:00Z",
                  "conversationId":"conv-1",
                  "runId":"run-1",
                  "message":"done",
                  "source":"python",
                  "unexpected":true
                }
                """);

        assertThatThrownBy(() -> validator.validateEventJson(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match schema");
    }

}
