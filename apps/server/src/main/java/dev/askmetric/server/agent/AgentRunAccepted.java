package dev.askmetric.server.agent;

/**
 * Java 接受 Agent Run 后返回给客户端的定位信息。
 *
 * @param conversationId 所属对话 ID
 * @param runId 新创建的 Agent Run ID
 * @param eventsUrl 用于订阅该运行 SSE 事件流的相对地址
 */
public record AgentRunAccepted(
        String conversationId,
        String runId,
        String eventsUrl
) {
}
