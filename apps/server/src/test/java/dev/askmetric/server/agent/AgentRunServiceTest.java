package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AgentRunServiceTest {
    @Test
    void publishesPersistedPythonEventsOnlyAfterTheTransactionCommits() {
        var mapper = mock(AgentRunMapper.class);
        var sseHub = mock(AgentRunSseHub.class);
        var event = event("evt-progress", AgentRunEventType.PROGRESS, 2);
        when(mapper.appendExternalEvent(
                        event.getEventId(), event.getRunId(), event.getSequence(), event.getEventType(),
                        event.getOccurredAt(), event.getConversationId(), event.getMessage(), event.getSource()))
                .thenReturn(1);
        var service = service(sseHub, mapper, mock(AgentRunOutboxMapper.class));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.acceptEvent(event);

            verify(sseHub, never()).publishPersisted(eq("run-1"), any());
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
            verify(sseHub).publishPersisted(eq("run-1"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void resynchronizesSseWhenRocketMqRedeliversAnEventAlreadyInTheDatabase() {
        var mapper = mock(AgentRunMapper.class);
        var sseHub = mock(AgentRunSseHub.class);
        var event = event("evt-progress", AgentRunEventType.PROGRESS, 2);
        when(mapper.eventExists(event.getEventId(), event.getRunId())).thenReturn(true);

        service(sseHub, mapper, mock(AgentRunOutboxMapper.class)).acceptEvent(event);

        verify(sseHub).publishPersisted(eq("run-1"), any());
    }

    @Test
    void rejectsAnExternalEventThatWasNotPersistedOrPreviouslySeen() {
        var event = event("evt-invalid", AgentRunEventType.COMPLETED, 4);

        assertThatThrownBy(() -> service(
                        mock(AgentRunSseHub.class),
                        mock(AgentRunMapper.class),
                        mock(AgentRunOutboxMapper.class))
                .acceptEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent Run event was not accepted: evt-invalid");
    }

    @Test
    void ignoresAndResynchronizesALateExternalEventAfterPersistentCancellation() {
        var mapper = mock(AgentRunMapper.class);
        var sseHub = mock(AgentRunSseHub.class);
        var event = event("evt-late-completed", AgentRunEventType.COMPLETED, 4);
        when(mapper.isTerminal("run-1")).thenReturn(true);

        service(sseHub, mapper, mock(AgentRunOutboxMapper.class)).acceptEvent(event);

        verify(sseHub).publishPersisted(eq("run-1"), any());
    }

    @Test
    void persistsCancellationEnqueuesPythonCommandAndPublishesSse() throws Exception {
        var mapper = mock(AgentRunMapper.class);
        var outbox = mock(AgentRunOutboxMapper.class);
        var sseHub = mock(AgentRunSseHub.class);
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentRunEvent cancelled = event("agent_run_cancelled_run-1", AgentRunEventType.CANCELLED, 3);
        when(mapper.cancelRun(
                        "user-1", "workspace-demo", "conversation-1", "run-1",
                        "agent_run_cancelled_run-1", "用户主动停止分析"))
                .thenReturn(Optional.of(cancelled));
        when(outbox.enqueue(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<AgentRunEvent> result = new AgentRunService(
                        sseHub, mapper, outbox,
                        mock(dev.askmetric.server.analysis.AnalysisFindingService.class),
                        objectMapper, "request-topic")
                .cancel("user-1", "workspace-demo", "conversation-1", "run-1", "用户主动停止分析");

        assertThat(result).contains(cancelled);
        verify(sseHub).publishPersisted(eq("run-1"), any());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(
                eq("outbox_cancel_run-1"),
                eq("agent_run_cancel_request_run-1"),
                eq("run-1"),
                eq("request-topic"),
                payload.capture());
        assertThat(objectMapper.readTree(payload.getValue()).get("eventType").asText())
                .isEqualTo("agent.run.cancel.requested");
    }

    private static AgentRunService service(
            AgentRunSseHub sseHub, AgentRunMapper mapper, AgentRunOutboxMapper outbox) {
        return new AgentRunService(
                sseHub, mapper, outbox,
                mock(dev.askmetric.server.analysis.AnalysisFindingService.class),
                new ObjectMapper(), "request-topic");
    }

    private static AgentRunEvent event(String eventId, AgentRunEventType eventType, long sequence) {
        return new AgentRunEvent(
                eventId,
                1,
                eventType,
                sequence,
                Instant.parse("2026-09-06T02:00:00Z"),
                "conversation-1",
                "run-1",
                "event message",
                eventType == AgentRunEventType.CANCELLED ? AgentRunEventSource.JAVA : AgentRunEventSource.PYTHON);
    }
}
