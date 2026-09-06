package dev.askmetric.server.agent;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户停止 Analysis Task 时提供的审计原因。 */
@Data
@NoArgsConstructor
public class CancelAgentRunRequest {
    private String reason;
}
