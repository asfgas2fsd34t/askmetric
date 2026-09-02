package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Agent 对用户 Message 的一级意图解释。
 *
 * <p>这是消息路由分类，不是 Analysis Task 的状态。对已有分析目标补充条件、追问或继续分析，
 * 仍然属于 {@link #ANALYSIS}；只有明确要求暂停、恢复、取消或查看任务状态等生命周期操作时，
 * 才属于 {@link #TASK_CONTROL}。
 */
public enum AgentRunIntentRoute {
    /**
     * 普通对话或产品问答，不创建也不推进 Analysis Task。
     * 例如：“你好”“你是谁”“AskMetric 是什么”。
     */
    CHAT("chat"),
    /**
     * 需要创建或推进 Analysis Task 的分析请求。
     * 例如：“为什么 MRR 下降”“按地区拆分一下”“继续看近六个月趋势”。
     */
    ANALYSIS("analysis"),
    /**
     * 控制已有 Analysis Task 生命周期的请求，不负责补充分析内容。
     * 例如：“暂停任务”“取消当前分析”“恢复任务”“查看任务状态”。
     */
    TASK_CONTROL("task_control"),
    /**
     * 对 Action Proposal 作出明确审批决定的请求。
     * 例如：“批准创建跟进任务”“拒绝这个操作”。
     */
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

    /** 将 API 或幂等响应中的稳定路由值转换回枚举。 */
    @JsonCreator
    public static AgentRunIntentRoute fromWireValue(String wireValue) {
        for (AgentRunIntentRoute route : values()) {
            if (route.wireValue.equals(wireValue)) {
                return route;
            }
        }
        throw new IllegalArgumentException("Unsupported Agent Run intent route: " + wireValue);
    }
}
