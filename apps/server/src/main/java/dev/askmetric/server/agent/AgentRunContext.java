package dev.askmetric.server.agent;

import lombok.Data;

/**
 * Agent Run 的执行上下文：工作区、会话，以及代表其执行治理查询的发起人。
 */
@Data
public class AgentRunContext {
    private String workspaceId;
    private String conversationId;
    private String authorSubject;
}
