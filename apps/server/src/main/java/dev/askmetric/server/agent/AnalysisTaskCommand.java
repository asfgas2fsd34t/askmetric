package dev.askmetric.server.agent;

/** 明确分析任务命令的确定性分类，不作为持久化业务状态。 */
public enum AnalysisTaskCommand {
    /** 消息未携带明确的任务操作命令。 */
    NONE,
    /** 消息明确要求继续当前活动 Analysis Task。 */
    CONTINUE,
    /** 消息明确要求切换到新的分析目标。 */
    SWITCH,
    /** 已识别任务命令前缀，但缺少执行该命令所需的信息。 */
    INCOMPLETE
}
