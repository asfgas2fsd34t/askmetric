package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunServiceTest {
    @Test
    void persistsPythonEventsBeforeBuildingAnSseProjection() {
        var mapper = mock(AgentRunMapper.class);
        var store = new AgentRunStore();
        var event = new AgentRunEvent(
                "evt-progress", 1, AgentRunEventType.PROGRESS, 2,
                Instant.parse("2026-08-28T02:00:01Z"), "conversation-1", "run-1",
                "running", AgentRunEventSource.PYTHON);
        when(mapper.appendExternalEvent(
                event.getEventId(), event.getRunId(), event.getSequence(), event.getEventType(),
                event.getOccurredAt(), event.getConversationId(), event.getMessage(), event.getSource()))
                .thenReturn(1);
        when(mapper.workspaceId(event.getConversationId(), event.getRunId()))
                .thenReturn(Optional.of("workspace-demo"));
        when(mapper.eventsForRun(event.getConversationId(), event.getRunId())).thenReturn(java.util.List.of(event));

        new AgentRunService(store, mapper).acceptEvent(event);

        verify(mapper).appendExternalEvent(
                event.getEventId(), event.getRunId(), event.getSequence(), event.getEventType(),
                event.getOccurredAt(), event.getConversationId(), event.getMessage(), event.getSource());
    }

    @Test
    void ignoresAStaleEventSequenceDuringRedelivery() {
        var store = new AgentRunStore();
        store.create("run-1", "workspace-demo", "conversation-1");
        var accepted = new AgentRunEvent(
                "evt-accepted", 1, AgentRunEventType.ACCEPTED, 1,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "queued", AgentRunEventSource.JAVA);
        store.append(accepted);
        var progress = new AgentRunEvent(
                "evt-progress", 1, AgentRunEventType.PROGRESS, 2,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "running", AgentRunEventSource.PYTHON);
        var redeliveredProgress = new AgentRunEvent(
                "evt-progress-redelivered", 1, AgentRunEventType.PROGRESS, 2,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "running", AgentRunEventSource.PYTHON);

        assertThat(store.append(progress)).isTrue();
        assertThat(store.append(redeliveredProgress)).isFalse();
        assertThat(store.snapshot("run-1")).containsExactly(accepted, progress);
    }

    @Test
    void ignoresAnEventRedeliveredWithTheSameEventId() {
        var store = new AgentRunStore();
        store.create("run-1", "workspace-demo", "conversation-1");
        var accepted = new AgentRunEvent(
                "evt-accepted", 1, AgentRunEventType.ACCEPTED, 1,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "queued", AgentRunEventSource.JAVA);

        assertThat(store.append(accepted)).isTrue();
        assertThat(store.append(accepted)).isFalse();
        assertThat(store.snapshot("run-1")).containsExactly(accepted);
    }

    @Test
    void rejectsAnEventThatSkipsASequenceNumber() {
        var store = new AgentRunStore();
        store.create("run-1", "workspace-demo", "conversation-1");
        store.append(new AgentRunEvent(
                "evt-accepted", 1, AgentRunEventType.ACCEPTED, 1,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "queued", AgentRunEventSource.JAVA));

        assertThatThrownBy(() -> store.append(new AgentRunEvent(
                "evt-progress", 1, AgentRunEventType.PROGRESS, 3,
                Instant.parse("2026-08-28T02:00:01Z"), "conversation-1", "run-1",
                "running", AgentRunEventSource.PYTHON)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event sequence must be 2 for run run-1");
    }

    @Test
    void rejectsAnEventForAnUnknownRun() {
        var store = new AgentRunStore();
        var event = new AgentRunEvent(
                "evt-unknown", 1, AgentRunEventType.PROGRESS, 1,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-unknown",
                "running", AgentRunEventSource.PYTHON);

        assertThatThrownBy(() -> store.append(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent Run does not exist: run-unknown");
    }

    @Test
    void ignoresEventsAfterTheRunReachesATerminalState() {
        var store = new AgentRunStore();
        store.create("run-1", "workspace-demo", "conversation-1");
        store.append(new AgentRunEvent(
                "evt-accepted", 1, AgentRunEventType.ACCEPTED, 1,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "queued", AgentRunEventSource.JAVA));
        store.append(new AgentRunEvent(
                "evt-progress", 1, AgentRunEventType.PROGRESS, 2,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "running", AgentRunEventSource.PYTHON));
        store.append(new AgentRunEvent(
                "evt-completed", 1, AgentRunEventType.COMPLETED, 3,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "done", AgentRunEventSource.PYTHON));

        assertThat(store.append(new AgentRunEvent(
                "evt-late-progress", 1, AgentRunEventType.PROGRESS, 4,
                Instant.parse("2026-08-28T02:00:01Z"), "conversation-1", "run-1",
                "late", AgentRunEventSource.PYTHON))).isFalse();
        assertThat(store.snapshot("run-1")).hasSize(3);
    }

    @Test
    void rejectsSkippingTheRunningState() {
        var store = new AgentRunStore();
        store.create("run-1", "workspace-demo", "conversation-1");
        store.append(new AgentRunEvent(
                "evt-accepted", 1, AgentRunEventType.ACCEPTED, 1,
                Instant.parse("2026-08-28T02:00:00Z"), "conversation-1", "run-1",
                "queued", AgentRunEventSource.JAVA));

        assertThat(store.append(new AgentRunEvent(
                "evt-completed", 1, AgentRunEventType.COMPLETED, 2,
                Instant.parse("2026-08-28T02:00:01Z"), "conversation-1", "run-1",
                "done", AgentRunEventSource.PYTHON))).isFalse();
        assertThat(store.snapshot("run-1")).hasSize(1);
    }
}
