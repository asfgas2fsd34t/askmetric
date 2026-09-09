package dev.askmetric.server.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.askmetric.server.analysis.KnowledgeCitation;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent Run 事件携带的结构化已验证发现：结论必须可追溯到指标版本和 Evidence Snapshot，
 * 知识引用只作为带出处的佐证，不影响 verified 判定。
 */
@Data
@NoArgsConstructor
public class AgentRunFinding {
    private String conclusion;
    private String metricDefinitionVersionId;
    private boolean verified;
    private List<String> evidenceSnapshotIds;
    private List<String> assumptions;
    private List<String> uncertainties;
    private List<KnowledgeCitation> knowledgeCitations;

    @JsonCreator
    public AgentRunFinding(
            @JsonProperty("conclusion") String conclusion,
            @JsonProperty("metricDefinitionVersionId") String metricDefinitionVersionId,
            @JsonProperty("verified") Boolean verified,
            @JsonProperty("evidenceSnapshotIds") List<String> evidenceSnapshotIds,
            @JsonProperty("assumptions") List<String> assumptions,
            @JsonProperty("uncertainties") List<String> uncertainties,
            @JsonProperty(value = "knowledgeCitations") List<KnowledgeCitation> knowledgeCitations) {
        if (conclusion == null || conclusion.isBlank() || conclusion.length() > 4000) {
            throw new IllegalArgumentException("finding.conclusion must be 1-4000 characters");
        }
        if (metricDefinitionVersionId == null || metricDefinitionVersionId.isBlank()) {
            throw new IllegalArgumentException("finding.metricDefinitionVersionId is required");
        }
        if (verified == null) {
            throw new IllegalArgumentException("finding.verified is required");
        }
        if (evidenceSnapshotIds == null || evidenceSnapshotIds.isEmpty() || evidenceSnapshotIds.size() > 10
                || evidenceSnapshotIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("finding.evidenceSnapshotIds must contain 1-10 non-blank ids");
        }
        this.assumptions = validateNotes(assumptions, "finding.assumptions");
        this.uncertainties = validateNotes(uncertainties, "finding.uncertainties");
        if (knowledgeCitations != null && knowledgeCitations.size() > 5) {
            throw new IllegalArgumentException("finding.knowledgeCitations must contain at most 5 citations");
        }
        this.conclusion = conclusion;
        this.metricDefinitionVersionId = metricDefinitionVersionId;
        this.verified = verified;
        this.evidenceSnapshotIds = List.copyOf(evidenceSnapshotIds);
        this.knowledgeCitations = knowledgeCitations == null ? List.of() : List.copyOf(knowledgeCitations);
    }

    private static List<String> validateNotes(List<String> notes, String field) {
        if (notes == null) {
            return List.of();
        }
        if (notes.size() > 10 || notes.stream().anyMatch(note -> note == null || note.isBlank() || note.length() > 500)) {
            throw new IllegalArgumentException(field + " must contain at most 10 notes of 1-500 characters");
        }
        return List.copyOf(notes);
    }
}
