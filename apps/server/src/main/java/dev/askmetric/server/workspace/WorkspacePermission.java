package dev.askmetric.server.workspace;

public enum WorkspacePermission {
    VIEW_WORKSPACE,
    VIEW_CONVERSATION,
    CREATE_CONVERSATION,
    CREATE_MESSAGE,
    VIEW_AGENT_RUN,
    /** 批准/拒绝操作提案与直建标准口径修订提案的治理权限（T23）。 */
    APPROVE_ACTION_PROPOSAL
}
