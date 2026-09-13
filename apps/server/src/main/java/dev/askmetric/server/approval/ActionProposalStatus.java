package dev.askmetric.server.approval;

/** 操作提案的生命周期：参数不可变，推进只改变状态字段。 */
public enum ActionProposalStatus {
    /** Agent 已提交，等待发起者显式确认创建。 */
    AWAITING_CONFIRMATION,
    /** 发起者已确认，等待有权限的成员审批（T23）。 */
    AWAITING_APPROVAL,
    /** 同一分析任务出现了新提案；本提案参数保持原样、不再可审批。 */
    SUPERSEDED,
    /** 发起者放弃了该提案；不会产生任何副作用。 */
    DISCARDED
}
