package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunEventContractTest {
    @Test
    void acceptsAValidVersionOneEvent() {
        var event = new AgentRunEvent(
                "evt-1", 1, AgentRunEventType.COMPLETED, 2,
                Instant.parse("2026-08-28T02:00:00Z"), "conv-1", "run-1",
                "Synthetic Agent Run completed", AgentRunEventSource.PYTHON);

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.sequence()).isEqualTo(2);
    }

}
