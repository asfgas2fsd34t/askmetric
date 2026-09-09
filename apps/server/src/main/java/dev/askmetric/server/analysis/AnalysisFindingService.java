package dev.askmetric.server.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 已验证发现的解析与读取：JSON 数组字段在边界处转换为领域类型。 */
@Service
public class AnalysisFindingService {
    private final AnalysisFindingMapper mapper;
    private final ObjectMapper objectMapper;

    public AnalysisFindingService(AnalysisFindingMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 持久化一条发现；治理校验在 insert 的 SQL 守卫中完成。 */
    @Transactional
    public int persist(
            String findingId,
            String runId,
            String metricDefinitionVersionId,
            boolean verified,
            String conclusion,
            List<String> evidenceSnapshotIds,
            List<String> assumptions,
            List<String> uncertainties) {
        return mapper.insert(
                findingId,
                runId,
                metricDefinitionVersionId,
                verified,
                conclusion,
                writeJson(evidenceSnapshotIds),
                writeJson(assumptions),
                writeJson(uncertainties));
    }

    /** 读取会话内当前用户有权查看的已验证发现。 */
    @Transactional(readOnly = true)
    public List<AnalysisFinding> list(String userSubject, String workspaceId, String conversationId) {
        return mapper.list(userSubject, workspaceId, conversationId).stream()
                .map(this::toDomain)
                .toList();
    }

    private AnalysisFinding toDomain(AnalysisFindingRecord record) {
        AnalysisFinding finding = new AnalysisFinding();
        finding.setFindingId(record.getFindingId());
        finding.setConversationId(record.getConversationId());
        finding.setAnalysisTaskId(record.getAnalysisTaskId());
        finding.setRunId(record.getRunId());
        finding.setMetricDefinitionVersionId(record.getMetricDefinitionVersionId());
        finding.setVerified(record.isVerified());
        finding.setConclusion(record.getConclusion());
        finding.setEvidenceSnapshotIds(readJson(record.getEvidenceSnapshotIdsJson()));
        finding.setAssumptions(readJson(record.getAssumptionsJson()));
        finding.setUncertainties(readJson(record.getUncertaintiesJson()));
        finding.setCreatedAt(record.getCreatedAt());
        return finding;
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Analysis Finding 字段", exception);
        }
    }

    private List<String> readJson(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析 Analysis Finding 字段", exception);
        }
    }
}
