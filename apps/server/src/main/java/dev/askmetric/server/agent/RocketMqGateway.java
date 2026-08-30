package dev.askmetric.server.agent;

/**
 * 隔离 Agent Run 领域服务与 RocketMQ 客户端生命周期，便于本地禁用消息队列并替换测试实现。
 */
public interface RocketMqGateway extends AutoCloseable {
    void publish(AgentRunRequest request);

    @Override
    void close();
}
