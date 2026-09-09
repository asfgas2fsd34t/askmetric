package dev.askmetric.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RocketMQ 5 的生产适配器：发布 Java 创建的 Agent Run 请求，并消费 Python 返回的运行事件。
 */
@Component
@ConditionalOnProperty(name = "askmetric.rocketmq.enabled", havingValue = "true")
public class LiveRocketMqGateway implements RocketMqGateway {
    private static final Logger LOG = LoggerFactory.getLogger(LiveRocketMqGateway.class);

    private final ObjectMapper objectMapper;
    private final AgentRunContractValidator contractValidator;
    private final ObjectProvider<AgentRunService> serviceProvider;
    private final String endpoints;
    private final String requestTopic;
    private final String eventTopic;
    private final String consumerGroup;
    private ClientServiceProvider provider;
    private Producer producer;
    private PushConsumer consumer;

    public LiveRocketMqGateway(
            ObjectMapper objectMapper,
            AgentRunContractValidator contractValidator,
            ObjectProvider<AgentRunService> serviceProvider,
            @Value("${askmetric.rocketmq.endpoints}") String endpoints,
            @Value("${askmetric.rocketmq.request-topic}") String requestTopic,
            @Value("${askmetric.rocketmq.event-topic}") String eventTopic,
            @Value("${askmetric.rocketmq.consumer-group}") String consumerGroup) {
        this.objectMapper = objectMapper;
        this.contractValidator = contractValidator;
        this.serviceProvider = serviceProvider;
        this.endpoints = endpoints;
        this.requestTopic = requestTopic;
        this.eventTopic = eventTopic;
        this.consumerGroup = consumerGroup;
    }

    @PostConstruct
    void start() throws ClientException {
        provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .build();
        producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(requestTopic)
                .build();
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(consumerGroup)
                .setSubscriptionExpressions(Map.of(eventTopic, new FilterExpression()))
                .setMessageListener(this::consumeEvent)
                .build();
    }

    @Override
    public void publish(AgentRunRequest request) {
        publish(requestTopic, request);
    }

    @Override
    public void publish(String topic, AgentRunRequest request) {
        try {
            contractValidator.validateRequest(request);
            var message = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("agent-run")
                    .setKeys(request.getEventId())
                    .setBody(objectMapper.writeValueAsBytes(request))
                    .build();
            producer.send(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to publish Agent Run request", exception);
        }
    }

    private ConsumeResult consumeEvent(MessageView messageView) {
        try {
            ByteBuffer body = messageView.getBody();
            byte[] bytes = new byte[body.remaining()];
            body.get(bytes);
            var payload = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            contractValidator.validateEventJson(payload);
            serviceProvider.getObject().acceptEvent(objectMapper.treeToValue(payload, AgentRunEvent.class));
            return ConsumeResult.SUCCESS;
        } catch (Exception exception) {
            // 未验证或未持久化的事件必须触发队列重投，不能确认消费后静默丢失。
            LOG.warn("Agent Run 事件消费失败，交还 RocketMQ 重投: {}",
                    exception.getMessage(), exception);
            return ConsumeResult.FAILURE;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            if (consumer != null) consumer.close();
        } catch (Exception ignored) {
        }
        try {
            if (producer != null) producer.close();
        } catch (Exception ignored) {
        }
    }
}
