package dev.askmetric.server.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.analysis.AnalysisTask;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageProcessed {
    private ConversationMessage userMessage;
    private ConversationMessage assistantMessage;
    private PersistedAgentRun agentRun;
    private AnalysisTask analysisTask;
}
