package dev.askmetric.server.analysis;

import java.util.List;
import lombok.Data;

/**
 * 由 Agent Run 产出、经 Java 校验并持久化的结构化已验证发现。
 */
@Data
public class AnalysisFinding {
    private String findingId;
    private String conversationId;
    private String analysisTaskId;
    private String runId;
    private String metricDefinitionVersionId;
    private boolean verified;
    private String conclusion;
    private List<String> evidenceSnapshotIds;
    private List<String> assumptions;
    private List<String> uncertainties;
    private List<KnowledgeCitation> knowledgeCitations = List.of();
    private java.time.Instant createdAt;
}
