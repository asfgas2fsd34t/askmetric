package dev.askmetric.server.analysis;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AnalysisTask {
    private String analysisTaskId;
    private String conversationId;
    private String goal;
    private AnalysisTaskStatus status;
    private String sourceAgentRunId;
    private Instant createdAt;
}
