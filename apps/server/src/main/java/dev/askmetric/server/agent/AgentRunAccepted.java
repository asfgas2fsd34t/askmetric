package dev.askmetric.server.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Java 接受 Agent Run 后返回给客户端的定位信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunAccepted {
    private String conversationId;
    private String runId;
    private String eventsUrl;
}
