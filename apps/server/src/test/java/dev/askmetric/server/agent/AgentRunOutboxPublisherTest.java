package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunOutboxPublisherTest {
    private final AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
    private final RocketMqGateway gateway = mock(RocketMqGateway.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
            outboxMapper, gateway, objectMapper, 3);

    @Test
    void publishesClaimedRequestAndMarksItPublished() {
        AgentRunOutbox record = record(1);
        when(outboxMapper.claim(20)).thenReturn(List.of(record));

        publisher.publishPending();

        verify(gateway).publish(eq("askmetric-agent-run-request"), any(AgentRunRequest.class));
        verify(outboxMapper).markPublished("outbox-1");
    }

    @Test
    void schedulesRetryWhenPublishingFails() {
        AgentRunOutbox record = record(2);
        when(outboxMapper.claim(20)).thenReturn(List.of(record));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(gateway).publish(any(String.class), any(AgentRunRequest.class));

        assertThatCode(publisher::publishPending).doesNotThrowAnyException();

        verify(outboxMapper).markFailure(eq("outbox-1"), eq(3), eq(2), eq("broker unavailable"));
    }

    private AgentRunOutbox record(int attempts) {
        AgentRunRequest request = new AgentRunRequest(
                "request-1", 1, AgentRunEventType.REQUESTED, 1,
                Instant.parse("2026-09-02T00:00:00Z"), "conversation-1", "run-1", "MRR");
        AgentRunOutbox record = new AgentRunOutbox();
        record.setOutboxId("outbox-1");
        record.setTopic("askmetric-agent-run-request");
        record.setAttempts(attempts);
        try {
            record.setPayload(objectMapper.writeValueAsString(request));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return record;
    }
}
