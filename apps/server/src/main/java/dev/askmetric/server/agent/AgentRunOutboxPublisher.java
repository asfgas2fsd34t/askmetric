package dev.askmetric.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期领取并发布 Agent Run Outbox，失败后有限重试并进入死信状态。 */
@Component
@ConditionalOnProperty(name = "askmetric.rocketmq.enabled", havingValue = "true")
public class AgentRunOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunOutboxPublisher.class);

    private final AgentRunOutboxMapper outboxMapper;
    private final RocketMqGateway gateway;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public AgentRunOutboxPublisher(
            AgentRunOutboxMapper outboxMapper,
            RocketMqGateway gateway,
            ObjectMapper objectMapper,
            @Value("${askmetric.outbox.max-attempts:5}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("askmetric.outbox.max-attempts must be positive");
        }
        this.outboxMapper = outboxMapper;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    /** 每秒轮询一小批 Outbox，避免发布线程长期占用业务请求线程。 */
    @Scheduled(fixedDelayString = "${askmetric.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        List<AgentRunOutbox> records = outboxMapper.claim(20);
        for (AgentRunOutbox record : records) {
            try {
                gateway.publish(record.getTopic(), objectMapper.readValue(record.getPayload(), AgentRunRequest.class));
                outboxMapper.markPublished(record.getOutboxId());
            } catch (Exception exception) {
                int delaySeconds = Math.min(60, 1 << Math.min(record.getAttempts() - 1, 6));
                String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                LOG.warn("Agent Run Outbox 发布失败 outboxId={} attempt={}", record.getOutboxId(), record.getAttempts(), exception);
                outboxMapper.markFailure(record.getOutboxId(), maxAttempts, delaySeconds, error.substring(0, Math.min(error.length(), 1000)));
            }
        }
    }
}
