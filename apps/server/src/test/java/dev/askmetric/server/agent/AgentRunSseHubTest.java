package dev.askmetric.server.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentRunSseHubTest {
    @Test
    void replaysOnlyEventsAfterTheClientSequenceAndClosesAtTheTerminalEvent() throws Exception {
        var hub = new AgentRunSseHub();
        var emitter = mock(SseEmitter.class);

        hub.registerAndReplay("run-1", 1, emitter, () -> List.of(
                event("accepted", AgentRunEventType.ACCEPTED, 1),
                event("progress", AgentRunEventType.PROGRESS, 2),
                event("completed", AgentRunEventType.COMPLETED, 3)));

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void publishesMissingDatabaseEventsOnlyOnce() throws Exception {
        var hub = new AgentRunSseHub();
        var emitter = mock(SseEmitter.class);
        AgentRunEvent accepted = event("accepted", AgentRunEventType.ACCEPTED, 1);
        AgentRunEvent progress = event("progress", AgentRunEventType.PROGRESS, 2);

        hub.registerAndReplay("run-1", 0, emitter, () -> List.of(accepted));
        clearInvocations(emitter);
        hub.publishPersisted("run-1", () -> List.of(accepted, progress));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        clearInvocations(emitter);
        hub.publishPersisted("run-1", () -> List.of(accepted, progress));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
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
                AgentRunEventSource.PYTHON);
    }
}
