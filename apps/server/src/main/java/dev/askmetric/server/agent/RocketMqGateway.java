package dev.askmetric.server.agent;

/**
 * 隔离 Agent Run 领域服务与 RocketMQ 客户端生命周期，便于本地禁用消息队列并替换测试实现。
 */
public interface RocketMqGateway extends AutoCloseable {
    void publish(AgentRunRequest request);

    /** 按 Outbox 记录指定目标 Topic 发布；旧探针实现默认沿用自身配置。 */
    default void publish(String topic, AgentRunRequest request) {
        publish(request);
    }

    @Override
    void close();
}
