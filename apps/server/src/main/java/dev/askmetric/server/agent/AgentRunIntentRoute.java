package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonValue;

/** Agent 对用户 Message 的一级意图解释。 */
public enum AgentRunIntentRoute {
    /** 普通对话，不推进 Analysis Task。 */
    CHAT("chat"),
    /** 需要创建或推进 Analysis Task 的分析请求。 */
    ANALYSIS("analysis"),
    /** 控制已有 Analysis Task 的请求。 */
    TASK_CONTROL("task_control"),
    /** 对 Action Proposal 作出审批决定的请求。 */
    APPROVAL("approval");

    /** 跨 HTTP API 使用的小写稳定值。 */
    private final String wireValue;

    AgentRunIntentRoute(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 返回 API 中使用的稳定意图值。 */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
