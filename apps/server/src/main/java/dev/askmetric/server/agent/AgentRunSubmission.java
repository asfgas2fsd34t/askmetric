package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业务用户从对话中提交的一次 Agent Run 输入。
 */
@Data
@NoArgsConstructor
public class AgentRunSubmission {
    private String message;

    @JsonCreator
    public AgentRunSubmission(@JsonProperty("message") String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        this.message = message;
    }
}
