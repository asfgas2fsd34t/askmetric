package dev.askmetric.server.agent;

/** Agent Run Outbox 的投递生命周期。 */
public enum AgentRunOutboxStatus {
    /** 等待发布。 */
    PENDING,
    /** 已被一个发布器租约领取。 */
    PUBLISHING,
    /** 已成功发布到 RocketMQ。 */
    PUBLISHED,
    /** 达到最大重试次数后进入死信状态。 */
    DEAD_LETTER
}
