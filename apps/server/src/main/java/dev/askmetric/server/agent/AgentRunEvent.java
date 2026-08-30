package dev.askmetric.server.agent;

import java.time.Instant;

/**
 * Python 或 Java 为某次 Agent Run 追加的版本化生命周期事件。
 *
 * @param eventId 事件的全局唯一 ID，用于至少一次投递时的幂等去重
 * @param schemaVersion JSON 契约的版本号，当前固定为 1
 * @param eventType 生命周期事件种类
 * @param sequence 该 Agent Run 内严格递增的事件序号
 * @param occurredAt 事件产生时的 UTC 时间
 * @param conversationId 该运行所属的对话 ID
 * @param runId Java 侧持久化的 Agent Run ID
 * @param message 可展示给客户端并保留在审计链路中的状态说明
 * @param source 产生事件的已登记应用
 */
public record AgentRunEvent(
        String eventId,
        int schemaVersion,
        AgentRunEventType eventType,
        long sequence,
        Instant occurredAt,
        String conversationId,
        String runId,
        String message,
        AgentRunEventSource source
) {
    public AgentRunEvent {
        requireText(eventId, "eventId");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType is not supported");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        requireText(conversationId, "conversationId");
        requireText(runId, "runId");
        requireText(message, "message");
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (message.length() > 4000) {
            throw new IllegalArgumentException("message must be at most 4000 characters");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

}
