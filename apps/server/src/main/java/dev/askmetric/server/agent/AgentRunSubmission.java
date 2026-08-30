package dev.askmetric.server.agent;

/**
 * 业务用户从对话中提交的一次 Agent Run 输入。
 *
 * @param message 触发本次运行的原始用户消息
 */
public record AgentRunSubmission(String message) {
    public AgentRunSubmission {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }
}
