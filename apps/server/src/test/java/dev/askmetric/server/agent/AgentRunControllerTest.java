package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRunControllerTest {
    @Test
    void includesRunLocationWhenPublishingFails() {
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
        var controller = new AgentRunController(new AgentRunService(store, gateway));

        var response = controller.submit("conversation-1", new AgentRunSubmission("hello"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isInstanceOf(AgentRunAccepted.class);
        var accepted = (AgentRunAccepted) response.getBody();
        assertThat(accepted.runId()).isEqualTo(published.get().runId());
        assertThat(accepted.eventsUrl()).contains(accepted.runId());
    }
}
