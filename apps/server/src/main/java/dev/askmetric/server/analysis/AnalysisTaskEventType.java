package dev.askmetric.server.analysis;

/** Analysis Task 生命周期中需要持久化审计的事件类型。 */
public enum AnalysisTaskEventType {
    /** 当前 Analysis Task 已被切换为等待输入，并关联到新的活动任务。 */
    SWITCHED
}
