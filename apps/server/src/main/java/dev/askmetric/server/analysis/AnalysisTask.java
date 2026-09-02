package dev.askmetric.server.analysis;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AnalysisTask {
    /** Analysis Task 的全局唯一标识。 */
    private String analysisTaskId;
    /** 所属 Conversation 标识。 */
    private String conversationId;
    /** 由业务用户确认或发起的分析目标。 */
    private String goal;
    /** 当前 Analysis Task 生命周期状态。 */
    private AnalysisTaskStatus status;
    /** 创建此 Analysis Task 的 Agent Run 标识。 */
    private String sourceAgentRunId;
    /** 创建时间。 */
    private Instant createdAt;
}
