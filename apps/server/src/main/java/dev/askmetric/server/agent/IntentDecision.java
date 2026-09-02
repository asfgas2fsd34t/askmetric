package dev.askmetric.server.agent;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IntentDecision {
    /** 用户消息的一级意图路由。 */
    private AgentRunIntentRoute route;
    /** 确定性路由结果的置信度。 */
    private BigDecimal confidence;
    /** 明确的 Analysis Task 操作命令；未识别时为 NONE。 */
    private AnalysisTaskCommand analysisTaskCommand;
}
