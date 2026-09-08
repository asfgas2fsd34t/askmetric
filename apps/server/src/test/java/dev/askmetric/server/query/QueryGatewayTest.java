package dev.askmetric.server.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.askmetric.server.evidence.EvidenceSnapshotService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QueryGatewayTest {
    private final QuerySqlValidator validator = new QuerySqlValidator();
    private final EvidenceSnapshotService evidenceSnapshotService = mock(EvidenceSnapshotService.class);
    private final QueryAuditService auditService = mock(QueryAuditService.class);
    private final QueryGateway gateway = new QueryGateway(
            "jdbc:postgresql://localhost:1/unused",
            "user",
            "password",
            validator,
            auditService,
            evidenceSnapshotService);

    @Test
    void rejectsAQueryWithoutAnAnalysisRunAndWritesRejectedAudit() {
        QueryRequest request = new QueryRequest();
        request.setWorkspaceId("workspace-1");
        request.setRunId("run-other-workspace");
        request.setSql("select month_start from demo_warehouse.monthly_mrr");
        when(evidenceSnapshotService.resolveAnalysisRun("user-1", "workspace-1", "run-other-workspace"))
                .thenThrow(new QueryValidationException("查询必须关联当前工作区内的分析型 Agent Run"));

        assertThatThrownBy(() -> gateway.execute("user-1", request))
                .isInstanceOf(QueryValidationException.class)
                .hasMessage("查询必须关联当前工作区内的分析型 Agent Run");

        ArgumentCaptor<QueryAuditRecord> audit = ArgumentCaptor.forClass(QueryAuditRecord.class);
        verify(auditService).save(audit.capture());
        assertThat(audit.getValue().getStatus()).isEqualTo(QueryStatus.REJECTED);
        assertThat(audit.getValue().getRunId()).isEqualTo("run-other-workspace");
        assertThat(audit.getValue().getAnalysisTaskId()).isNull();
    }

    @Test
    void rejectsAParameterCountMismatchBeforeTouchingTheWarehouse() {
        QueryRequest request = new QueryRequest();
        request.setWorkspaceId("workspace-1");
        request.setRunId("run-1");
        request.setSql("select month_start from demo_warehouse.monthly_mrr where month_start = ?");
        request.setParameters(List.of());

        assertThatThrownBy(() -> gateway.execute("user-1", request))
                .isInstanceOf(QueryValidationException.class)
                .hasMessage("SQL 参数数量不匹配");

        ArgumentCaptor<QueryAuditRecord> audit = ArgumentCaptor.forClass(QueryAuditRecord.class);
        verify(auditService).save(audit.capture());
        assertThat(audit.getValue().getStatus()).isEqualTo(QueryStatus.REJECTED);
        assertThat(audit.getValue().getParameterCount()).isEqualTo(1);
    }
}
