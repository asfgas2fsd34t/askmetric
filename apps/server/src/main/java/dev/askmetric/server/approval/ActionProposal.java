package dev.askmetric.server.approval;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 等待人工决定、包含确切参数且不可变的操作提案；参数来自运行绑定的口径定义快照。 */
@Data
@NoArgsConstructor
public class ActionProposal {
    private String actionProposalId;
    private String workspaceId;
    private String conversationId;
    private String analysisTaskId;
    private String sourceAgentRunId;
    private ActionProposalType actionType;
    private ActionProposalStatus status;
    private String metricKey;
    private String versionLabel;
    private String calculationRule;
    private String timeBoundary;
    private String exclusions;
    private int policyVersion;
    private String idempotencyKey;
    private String proposedBy;
    private Instant confirmedAt;
    private String supersededBy;
    private Instant createdAt;
}
