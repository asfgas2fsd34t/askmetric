package dev.askmetric.server.memory;

import java.time.Instant;
import lombok.Data;

/**
 * 工作区用户记忆：只有经所有者明确确认的条目才可能进入 Agent 上下文。
 */
@Data
public class UserMemory {
    public enum Status {
        PROPOSED, CONFIRMED
    }

    private String userMemoryId;
    private String workspaceId;
    private String userSubject;
    private String content;
    private Status status;
    private String sourceConversationId;
    private String sourceAgentRunId;
    private Instant confirmedAt;
    private Instant createdAt;
}
