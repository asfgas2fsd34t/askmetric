package dev.askmetric.server.agent;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 待发布 Agent Run 请求的持久化 Outbox 记录。 */
@Data
@NoArgsConstructor
public class AgentRunOutbox {
    /** Outbox 记录唯一标识。 */
    private String outboxId;
    /** 跨服务请求事件唯一标识。 */
    private String eventId;
    /** 所属 Agent Run。 */
    private String runId;
    /** RocketMQ 目标 Topic。 */
    private String topic;
    /** 经过契约校验的 AgentRunRequest JSON。 */
    private String payload;
    /** 当前投递状态。 */
    private AgentRunOutboxStatus status;
    /** 已尝试发布次数。 */
    private int attempts;
    /** 下一次允许尝试的时间。 */
    private Instant nextAttemptAt;
    /** 发布租约过期时间。 */
    private Instant leaseUntil;
    /** 最近一次失败原因。 */
    private String lastError;
    /** 创建时间。 */
    private Instant createdAt;
    /** 成功发布的时间。 */
    private Instant publishedAt;
}
