package dev.askmetric.server.agent;

import java.time.Instant;

/**
 * Java 发布给 Python Agent Runtime 的版本化 Agent Run 请求事件。
 *
 * @param eventId 本次请求事件的全局唯一 ID，同时用作消费者去重键
 * @param schemaVersion JSON 契约的版本号，当前固定为 1
 * @param eventType 事件种类，当前固定为 {@link AgentRunEventType#REQUESTED}
 * @param sequence 该 Agent Run 内的事件序号，创建请求固定为 1
 * @param occurredAt Java 创建请求事件的 UTC 时间
 * @param conversationId 触发运行的对话 ID
 * @param runId Java 持久化的 Agent Run ID
 * @param message 由业务用户提交、交给 Agent 解释的消息正文
 */
public record AgentRunRequest(
        String eventId,
        int schemaVersion,
        AgentRunEventType eventType,
        long sequence,
        Instant occurredAt,
        String conversationId,
        String runId,
        String message
) {
    public AgentRunRequest {
        requireText(eventId, "eventId");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (eventType != AgentRunEventType.REQUESTED) {
            throw new IllegalArgumentException("eventType must be agent.run.requested");
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
