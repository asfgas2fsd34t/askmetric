package dev.askmetric.server.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.askmetric.server.agent.AgentRunIntentRoute;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.query.QueryResult;
import dev.askmetric.server.query.QueryValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceSnapshotServiceTest {
    private static final String USER = "user-1";
    private static final String WORKSPACE = "workspace-1";
    private static final String RUN = "run-1";
    private static final String TASK = "analysis-task-1";
    private static final String QUERY = "query-1";
    private static final String SOURCE_TABLE = "demo_warehouse.monthly_mrr";

    private final AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
    private final dev.askmetric.server.query.QueryAuditMapper queryAuditMapper =
            mock(dev.askmetric.server.query.QueryAuditMapper.class);
    private final EvidenceSnapshotMapper evidenceSnapshotMapper = mock(EvidenceSnapshotMapper.class);
    private final EvidenceSnapshotService service = new EvidenceSnapshotService(
            new ObjectMapper().findAndRegisterModules(),
            new EvidencePolicy(),
            agentRunMapper,
            queryAuditMapper,
            evidenceSnapshotMapper);

    @Test
    void resolvesTheAnalysisTaskFromAnAnalysisRun() {
        PersistedAgentRun run = analysisRun();
        when(agentRunMapper.findAnalysisRun(USER, WORKSPACE, RUN)).thenReturn(Optional.of(run));

        assertThat(service.resolveAnalysisRun(USER, WORKSPACE, RUN)).isSameAs(run);
    }

    @Test
    void rejectsAQueryWithoutAnAnalysisRun() {
        when(agentRunMapper.findAnalysisRun(USER, WORKSPACE, RUN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveAnalysisRun(USER, WORKSPACE, RUN))
                .isInstanceOf(QueryValidationException.class)
                .hasMessage("查询必须关联当前工作区内的分析型 Agent Run");
    }

    @Test
    void preparesAnImmutableEvidenceSnapshotRecord() {
        QueryResult result = queryResult();
        new EvidencePolicy().apply(result, SOURCE_TABLE);

        EvidenceSnapshotRecord record = service.prepare(WORKSPACE, analysisRun(), result, SOURCE_TABLE);

        assertThat(record.getEvidenceSnapshotId()).startsWith("evidence_snapshot_");
        assertThat(record.getWorkspaceId()).isEqualTo(WORKSPACE);
        assertThat(record.getAnalysisTaskId()).isEqualTo(TASK);
        assertThat(record.getRunId()).isEqualTo(RUN);
        assertThat(record.getQueryId()).isEqualTo(QUERY);
        assertThat(record.getSourceTable()).isEqualTo(SOURCE_TABLE);
        assertThat(record.getSourceRange()).isEqualTo("2025-01-01 至 2025-06-01");
        assertThat(record.getColumnsJson()).isEqualTo("[\"month_start\",\"ending_mrr_cents\"]");
        assertThat(record.getRowsJson()).isEqualTo(
                "[{\"month_start\":\"2025-01-01\",\"ending_mrr_cents\":320000},{\"month_start\":\"2025-06-01\",\"ending_mrr_cents\":190000}]");
        assertThat(record.getRowCount()).isEqualTo(2);
        assertThat(record.getSnapshotHash()).hasSize(64);
        assertThat(record.getCreatedAt()).isNotNull();
    }

    @Test
    void persistsSuccessAuditAndEvidenceSnapshotTogether() {
        QueryResult result = queryResult();
        dev.askmetric.server.query.QueryAuditRecord audit = new dev.askmetric.server.query.QueryAuditRecord();
        audit.setWorkspaceId(WORKSPACE);
        when(queryAuditMapper.insert(any())).thenReturn(1);
        when(evidenceSnapshotMapper.insert(any())).thenReturn(1);

        EvidenceSnapshotRecord record = service.persistSuccess(
                audit, analysisRun(), result, SOURCE_TABLE);

        assertThat(record.getEvidenceSnapshotId()).isNotBlank();
        assertThat(result.getEvidenceSnapshotId()).isNull();
        verify(queryAuditMapper).insert(audit);
        verify(evidenceSnapshotMapper).insert(record);
    }

    @Test
    void listsSnapshotsAsDomainObjectsForAConversation() {
        EvidenceSnapshotRecord record = preparedRecord();
        when(evidenceSnapshotMapper.list(USER, WORKSPACE, "conversation-1")).thenReturn(List.of(record));

        List<EvidenceSnapshot> snapshots = service.list(USER, WORKSPACE, "conversation-1");

        assertThat(snapshots).hasSize(1);
        EvidenceSnapshot snapshot = snapshots.getFirst();
        assertThat(snapshot.getEvidenceSnapshotId()).isEqualTo(record.getEvidenceSnapshotId());
        assertThat(snapshot.getColumns()).containsExactly("month_start", "ending_mrr_cents");
        assertThat(snapshot.getRows()).isEqualTo(List.of(
                Map.of("month_start", "2025-01-01", "ending_mrr_cents", 320000),
                Map.of("month_start", "2025-06-01", "ending_mrr_cents", 190000)));
    }

    @Test
    void rejectsAnEvidenceSnapshotWhenStoredHashDoesNotMatchContent() {
        EvidenceSnapshotRecord corrupted = preparedRecord();
        corrupted.setSnapshotHash("0".repeat(64));
        when(evidenceSnapshotMapper.list(USER, WORKSPACE, "conversation-1"))
                .thenReturn(List.of(corrupted));

        assertThatThrownBy(() -> service.list(USER, WORKSPACE, "conversation-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Evidence Snapshot 完整性校验失败");
    }

    private static PersistedAgentRun analysisRun() {
        PersistedAgentRun run = new PersistedAgentRun();
        run.setRunId(RUN);
        run.setConversationId("conversation-1");
        run.setIntentRoute(AgentRunIntentRoute.ANALYSIS);
        run.setIntentConfidence(BigDecimal.ONE);
        run.setAnalysisTaskId(TASK);
        run.setCreatedAt(Instant.parse("2026-09-08T00:00:00Z"));
        return run;
    }

    private static QueryResult queryResult() {
        QueryResult result = new QueryResult();
        result.setQueryId(QUERY);
        result.setColumns(List.of("month_start", "ending_mrr_cents"));
        result.setRows(List.of(
                new java.util.LinkedHashMap<>(Map.of(
                        "month_start", "2025-01-01", "ending_mrr_cents", 320000)),
                new java.util.LinkedHashMap<>(Map.of(
                        "month_start", "2025-06-01", "ending_mrr_cents", 190000))));
        result.setRowCount(2);
        result.setDurationMs(25);
        return result;
    }

    private EvidenceSnapshotRecord preparedRecord() {
        QueryResult result = queryResult();
        return service.prepare(WORKSPACE, analysisRun(), result, SOURCE_TABLE);
    }
}
