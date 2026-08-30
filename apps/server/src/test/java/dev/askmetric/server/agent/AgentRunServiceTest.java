package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRunServiceTest {
    @Test
    void submitsTrimmedMessageAndCreatesQueuedEventBeforePublishing() {
        var store = new AgentRunStore();
        var published = new AtomicReference<AgentRunRequest>();
        var gateway = new RocketMqGateway() {
            @Override
            public void publish(AgentRunRequest request) {
                published.set(request);
            }

            @Override
            public void close() {
            }
        };

        var accepted = new AgentRunService(store, gateway)
                .submit("workspace-demo", "conversation-1", new AgentRunSubmission("  hello  "));

        assertThat(published.get()).isNotNull();
        assertThat(published.get().getMessage()).isEqualTo("hello");
        assertThat(store.snapshot(accepted.getRunId()))
                .extracting(AgentRunEvent::getEventType)
                .containsExactly(AgentRunEventType.ACCEPTED);
    }

    @Test
    void onlyReplaysARunWithinItsOwningConversation() {
        var store = new AgentRunStore();
        var gateway = new RocketMqGateway() {
            @Override
            public void publish(AgentRunRequest request) {
            }

            @Override
            public void close() {
            }
        };

        var accepted = new AgentRunService(store, gateway)
                .submit("workspace-demo", "conversation-1", new AgentRunSubmission("hello"));

        assertThat(new AgentRunService(store, gateway)
                .exists("workspace-demo", "conversation-1", accepted.getRunId())).isTrue();
        assertThat(new AgentRunService(store, gateway)
                .exists("workspace-growth", "conversation-1", accepted.getRunId())).isFalse();
        assertThat(new AgentRunService(store, gateway)
                .exists("workspace-demo", "conversation-2", accepted.getRunId())).isFalse();
    }

    @Test
    void recordsAFailedTerminalEventWhenPublishingCannotStart() {
        var store = new AgentRunStore();
        var published = new AtomicReference<AgentRunRequest>();
        var gateway = new RocketMqGateway() {
            @Override
            public void publish(AgentRunRequest request) {
                published.set(request);
                throw new IllegalStateException("broker unavailable");
            }

            @Override
            public void close() {
            }
        };

        var thrown = catchThrowable(() -> new AgentRunService(store, gateway)
                .submit("workspace-demo", "conversation-1", new AgentRunSubmission("hello")));
        assertThat(thrown)
                .isInstanceOf(AgentRunPublishException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        assertThat(((AgentRunPublishException) thrown).accepted())
                .extracting(AgentRunAccepted::getRunId)
                .isEqualTo(published.get().getRunId());
        assertThat(store.snapshot(published.get().getRunId()))
                .extracting(AgentRunEvent::getEventType)
                .containsExactly(AgentRunEventType.ACCEPTED, AgentRunEventType.FAILED);
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
