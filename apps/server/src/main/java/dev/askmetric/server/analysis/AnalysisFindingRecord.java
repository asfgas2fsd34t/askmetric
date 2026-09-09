package dev.askmetric.server.analysis;

import lombok.Data;

/** analysis_finding 表的原始行；JSON 数组字段由服务层解析为领域对象。 */
@Data
public class AnalysisFindingRecord {
    private String findingId;
    private String conversationId;
    private String analysisTaskId;
    private String runId;
    private String metricDefinitionVersionId;
    private boolean verified;
    private String conclusion;
    private String evidenceSnapshotIdsJson;
    private String assumptionsJson;
    private String uncertaintiesJson;
    private java.time.Instant createdAt;
}
