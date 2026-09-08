package dev.askmetric.server.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.query.QueryAuditMapper;
import dev.askmetric.server.query.QueryAuditRecord;
import dev.askmetric.server.query.QueryResult;
import dev.askmetric.server.query.QueryValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 构造和读取受治理 Evidence Snapshot，并保证成功查询的审计与证据同事务落库。 */
@Service
public class EvidenceSnapshotService {
    private final ObjectMapper objectMapper;
    private final EvidencePolicy evidencePolicy;
    private final AgentRunMapper agentRunMapper;
    private final QueryAuditMapper queryAuditMapper;
    private final EvidenceSnapshotMapper evidenceSnapshotMapper;

    public EvidenceSnapshotService(
            ObjectMapper objectMapper,
            EvidencePolicy evidencePolicy,
            AgentRunMapper agentRunMapper,
            QueryAuditMapper queryAuditMapper,
            EvidenceSnapshotMapper evidenceSnapshotMapper) {
        this.objectMapper = objectMapper;
        this.evidencePolicy = evidencePolicy;
        this.agentRunMapper = agentRunMapper;
        this.queryAuditMapper = queryAuditMapper;
        this.evidenceSnapshotMapper = evidenceSnapshotMapper;
    }

    /** 校验查询必须关联当前用户 Workspace 内、已绑定 Analysis Task 的分析型 Agent Run。 */
    public PersistedAgentRun resolveAnalysisRun(String userSubject, String workspaceId, String runId) {
        if (runId == null || runId.isBlank()) {
            throw new QueryValidationException("查询必须关联 Agent Run");
        }
        return agentRunMapper.findAnalysisRun(userSubject, workspaceId, runId)
                .orElseThrow(() -> new QueryValidationException(
                        "查询必须关联当前工作区内的分析型 Agent Run"));
    }

    /**
     * 在独立事务中保存查询审计与 Evidence Snapshot。
     *
     * <p>失败和拒绝查询仍由 QueryAuditService 单独审计；成功查询的审计与证据必须一起提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EvidenceSnapshotRecord persistSuccess(
            QueryAuditRecord audit,
            PersistedAgentRun analysisRun,
            QueryResult result,
            String sourceTable) {
        evidencePolicy.apply(result, sourceTable);
        EvidenceSnapshotRecord record = prepare(audit.getWorkspaceId(), analysisRun, result, sourceTable);
        if (queryAuditMapper.insert(audit) != 1) {
            throw new IllegalStateException("查询审计记录保存失败");
        }
        if (evidenceSnapshotMapper.insert(record) != 1) {
            throw new IllegalStateException("Evidence Snapshot 保存失败");
        }
        return record;
    }

    /** 根据已裁剪的 Query Result 构造不可变快照持久化记录。 */
    public EvidenceSnapshotRecord prepare(
            String workspaceId, PersistedAgentRun analysisRun, QueryResult result, String sourceTable) {
        try {
            String columnsJson = objectMapper.writeValueAsString(result.getColumns());
            String rowsJson = objectMapper.writeValueAsString(result.getRows());
            EvidenceSnapshotRecord record = new EvidenceSnapshotRecord();
            record.setEvidenceSnapshotId("evidence_snapshot_" + UUID.randomUUID());
            record.setWorkspaceId(workspaceId);
            record.setAnalysisTaskId(analysisRun.getAnalysisTaskId());
            record.setRunId(analysisRun.getRunId());
            record.setQueryId(result.getQueryId());
            record.setSourceTable(sourceTable);
            record.setSourceRange(result.getSourceRange());
            record.setColumnsJson(columnsJson);
            record.setRowsJson(rowsJson);
            record.setRowCount(result.getRowCount());
            record.setDurationMs(result.getDurationMs());
            record.setSnapshotHash(snapshotHash(sourceTable, result.getSourceRange(), columnsJson, rowsJson));
            record.setCreatedAt(Instant.now());
            return record;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Evidence Snapshot", exception);
        }
    }

    /** 读取 Conversation 的证据快照并反序列化为前端可用的领域对象。 */
    public List<EvidenceSnapshot> list(String userSubject, String workspaceId, String conversationId) {
        return evidenceSnapshotMapper.list(userSubject, workspaceId, conversationId).stream()
                .map(this::snapshot)
                .toList();
    }

    private EvidenceSnapshot snapshot(EvidenceSnapshotRecord record) {
        try {
            String actualHash = snapshotHash(
                    record.getSourceTable(),
                    record.getSourceRange(),
                    record.getColumnsJson(),
                    record.getRowsJson());
            if (!actualHash.equals(record.getSnapshotHash())) {
                throw new IllegalStateException("Evidence Snapshot 完整性校验失败");
            }
            EvidenceSnapshot snapshot = new EvidenceSnapshot();
            snapshot.setEvidenceSnapshotId(record.getEvidenceSnapshotId());
            snapshot.setConversationId(record.getConversationId());
            snapshot.setAnalysisTaskId(record.getAnalysisTaskId());
            snapshot.setRunId(record.getRunId());
            snapshot.setQueryId(record.getQueryId());
            snapshot.setSourceTable(record.getSourceTable());
            snapshot.setSourceRange(record.getSourceRange());
            snapshot.setColumns(objectMapper.readValue(record.getColumnsJson(), new TypeReference<List<String>>() {}));
            snapshot.setRows(objectMapper.readValue(record.getRowsJson(), new TypeReference<List<Map<String, Object>>>() {}));
            snapshot.setRowCount(record.getRowCount());
            snapshot.setDurationMs(record.getDurationMs());
            snapshot.setCreatedAt(record.getCreatedAt());
            return snapshot;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法反序列化 Evidence Snapshot", exception);
        }
    }

    private String snapshotHash(
            String sourceTable, String sourceRange, String columnsJson, String rowsJson) {
        String canonical = sourceTable + "\n" + sourceRange + "\n" + columnsJson + "\n" + rowsJson;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
