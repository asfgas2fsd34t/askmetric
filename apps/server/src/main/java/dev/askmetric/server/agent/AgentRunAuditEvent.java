package dev.askmetric.server.agent;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条持久化的 Agent Run 审计事件。 */
@Data
@NoArgsConstructor
public class AgentRunAuditEvent {
    /** 审计事件的全局唯一标识。 */
    private String eventId;
    /** 事件所属的 Agent Run 标识。 */
    private String runId;
    /** 同一 Agent Run 内连续递增的事件序号。 */
    private long sequence;
    /** Agent Run 生命周期事件类型。 */
    private AgentRunEventType eventType;
    /** 事件产生时间。 */
    private Instant occurredAt;
    /** 可面向业务用户展示的事件说明。 */
    private String message;
    /** 事件生产者。 */
    private AgentRunEventSource source;
}
