package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 当前已登记的 Agent Run 事件生产者及其跨服务传输值。
 */
public enum AgentRunEventSource {
    /** Spring Boot Java 应用产生的事件。 */
    JAVA("java"),
    /** Python Agent Runtime 产生的事件。 */
    PYTHON("python");

    private final String wireValue;

    AgentRunEventSource(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 返回 JSON Schema 规定的生产者标识。
     */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * 将 RocketMQ JSON 中的生产者标识转换为 Java 枚举。
     */
    @JsonCreator
    public static AgentRunEventSource fromWireValue(String wireValue) {
        for (AgentRunEventSource source : values()) {
            if (source.wireValue.equals(wireValue)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unsupported Agent Run event source: " + wireValue);
    }
}
