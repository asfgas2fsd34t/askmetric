package dev.askmetric.server.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "askmetric.rocketmq.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledRocketMqGateway implements RocketMqGateway {
    @Override
    public void publish(AgentRunRequest request) {
        throw new IllegalStateException("RocketMQ gateway is disabled; set ROCKETMQ_ENABLED=true");
    }

    @Override
    public void close() {
    }
}
