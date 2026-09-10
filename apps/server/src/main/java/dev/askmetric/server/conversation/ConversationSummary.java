package dev.askmetric.server.conversation;

import java.time.Instant;
import lombok.Data;

/** 对话的有边界版本化派生摘要：记录覆盖的 Message 范围与版本，不替代原始 Message。 */
@Data
public class ConversationSummary {
    private String conversationSummaryId;
    private String workspaceId;
    private String conversationId;
    private int version;
    private long fromSequence;
    private long toSequence;
    private String summaryText;
    private String sourceAgentRunId;
    private Instant createdAt;
}
