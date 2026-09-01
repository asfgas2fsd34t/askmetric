package dev.askmetric.server.conversation;

import dev.askmetric.server.agent.PersistedAgentRun;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 普通聊天 Message、确定性回复与 Agent Run 均完成后的持久化结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageCompleted {
    /** 已持久化的用户 Message。 */
    private ConversationMessage userMessage;
    /** 已持久化的确定性助手回复。 */
    private ConversationMessage assistantMessage;
    /** 已完成的 Agent Run 及其审计事件。 */
    private PersistedAgentRun agentRun;
}
