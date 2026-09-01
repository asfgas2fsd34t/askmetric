package dev.askmetric.server.conversation;

import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.analysis.AnalysisTask;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ConversationSnapshot {
    private String conversationId;
    private String workspaceId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ConversationMessage> messages = new ArrayList<>();
    /** Conversation 内已持久化的 Agent Run。 */
    private List<PersistedAgentRun> agentRuns = new ArrayList<>();
    /** Conversation 内已持久化的 Analysis Task。 */
    private List<AnalysisTask> analysisTasks = new ArrayList<>();
}
