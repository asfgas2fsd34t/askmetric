package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Agent Run 生命周期事件类型及其跨服务传输值。
 */
public enum AgentRunEventType {
    /** Java 已创建运行并请求 Python 开始执行。 */
    REQUESTED("agent.run.requested"),
    /** Java 已接受运行并将其置于队列中。 */
    ACCEPTED("agent.run.accepted"),
    /** Python 正在处理运行。 */
    PROGRESS("agent.run.progress"),
    /** Python 已产生运行结果。 */
    COMPLETED("agent.run.completed"),
    /** Java 或 Python 已确认运行失败。 */
    FAILED("agent.run.failed");

    private final String wireValue;

    AgentRunEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 返回 JSON Schema 规定的事件类型字符串。
     */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * 判断该事件是否结束 Agent Run 生命周期。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * 将 RocketMQ JSON 中的事件类型转换为 Java 枚举。
     */
    @JsonCreator
    public static AgentRunEventType fromWireValue(String wireValue) {
        for (AgentRunEventType type : values()) {
            if (type.wireValue.equals(wireValue)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported Agent Run event type: " + wireValue);
    }
}
