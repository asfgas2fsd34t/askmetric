package dev.askmetric.server.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Java 业务数据库中持久化的 Agent Run 摘要。 */
@Data
@NoArgsConstructor
public class PersistedAgentRun {
    /** Agent Run 的全局唯一标识。 */
    private String runId;
    /** 所属 Conversation 标识。 */
    private String conversationId;
    /** 触发本次运行的用户 Message 标识。 */
    private String inputMessageId;
    /** 对用户 Message 的已记录意图解释。 */
    private AgentRunIntentRoute intentRoute;
    /** 意图路由的确定性置信度。 */
    private BigDecimal intentConfidence;
    /** 实际关联的 Analysis Task；普通聊天为空。 */
    private String analysisTaskId;
    /** 本次运行实际采用的指标定义版本；未执行指标分析时为空。 */
    private String metricDefinitionVersionId;
    /** 创建时间。 */
    private Instant createdAt;
    /** 按序持久化的运行审计事件。 */
    private List<AgentRunAuditEvent> auditEvents = new ArrayList<>();
}
