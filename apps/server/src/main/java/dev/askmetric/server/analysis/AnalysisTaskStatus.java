package dev.askmetric.server.analysis;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AnalysisTaskStatus {
    /** 正在推进的当前分析目标。 */
    ACTIVE("active"),
    /** 等待业务用户补充信息或重新继续的分析目标。 */
    WAITING_FOR_INPUT("waiting_for_input"),
    /** 等待人工审批操作提案的分析目标。 */
    WAITING_FOR_APPROVAL("waiting_for_approval"),
    /** 已产出可验证结果的分析目标。 */
    COMPLETED("completed"),
    /** 因无法恢复的错误而结束的分析目标。 */
    FAILED("failed"),
    /** 被业务用户明确取消的分析目标。 */
    CANCELLED("cancelled");

    /** 跨 HTTP API 使用的小写稳定值。 */
    private final String wireValue;

    AnalysisTaskStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
