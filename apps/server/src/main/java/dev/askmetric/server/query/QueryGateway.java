package dev.askmetric.server.query;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.evidence.EvidenceSnapshotRecord;
import dev.askmetric.server.evidence.EvidenceSnapshotService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Java 唯一拥有的受治理只读查询入口。 */
@Service
public class QueryGateway {
    private static final int MAX_ROWS = 1_000;
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final QuerySqlValidator validator;
    private final QueryAuditService auditService;
    private final EvidenceSnapshotService evidenceSnapshotService;

    public QueryGateway(
            @Value("${askmetric.query.datasource.url:jdbc:postgresql://localhost:5433/askmetric_demo_warehouse}") String url,
            @Value("${askmetric.query.datasource.username:askmetric_demo}") String username,
            @Value("${askmetric.query.datasource.password:askmetric_demo}") String password,
            QuerySqlValidator validator,
            QueryAuditService auditService,
            EvidenceSnapshotService evidenceSnapshotService) {
        DataSource dataSource = new DriverManagerDataSource(url, username, password);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.validator = validator;
        this.auditService = auditService;
        this.evidenceSnapshotService = evidenceSnapshotService;
    }

    /** 校验、执行并审计一次只读查询；成功结果会生成不可变 Evidence Snapshot。 */
    @Transactional(readOnly = true)
    public QueryResult execute(String userSubject, QueryRequest request) {
        String queryId = "query_" + UUID.randomUUID();
        long startedAt = System.nanoTime();
        int parameterCount = 0;
        try {
            parameterCount = validator.validate(request.getSql());
            List<Object> parameters = request.getParameters() == null ? List.of() : request.getParameters();
            if (parameters.size() != parameterCount) {
                throw new QueryValidationException("SQL 参数数量不匹配");
            }
            PersistedAgentRun analysisRun = evidenceSnapshotService.resolveAnalysisRun(
                    userSubject, request.getWorkspaceId(), request.getRunId());
            request.setAnalysisTaskId(analysisRun.getAnalysisTaskId());
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    connection -> {
                        PreparedStatement statement = connection.prepareStatement(request.getSql());
                        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        statement.setMaxRows(MAX_ROWS);
                        for (int i = 0; i < parameters.size(); i++) {
                            statement.setObject(i + 1, parameters.get(i));
                        }
                        return statement;
                    },
                    (resultSet, rowNumber) -> {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        var metadata = resultSet.getMetaData();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(metadata.getColumnLabel(i), resultSet.getObject(i));
                        }
                        return row;
                    });
            QueryResult result = result(queryId, QueryStatus.SUCCEEDED, rows, null, startedAt);
            QueryAuditRecord audit = auditRecord(userSubject, request, queryId, parameterCount, result);
            EvidenceSnapshotRecord snapshot = evidenceSnapshotService.persistSuccess(
                    audit, analysisRun, result, validator.sourceTables(request.getSql()));
            result.setEvidenceSnapshotId(snapshot.getEvidenceSnapshotId());
            return result;
        } catch (QueryValidationException exception) {
            QueryResult result = result(queryId, QueryStatus.REJECTED, List.of(), exception.getMessage(), startedAt);
            auditService.save(auditRecord(userSubject, request, queryId, parameterCount, result));
            throw exception;
        } catch (RuntimeException exception) {
            QueryResult result = result(queryId, QueryStatus.FAILED, List.of(), "查询执行失败", startedAt);
            auditService.save(auditRecord(userSubject, request, queryId, parameterCount, result));
            throw exception;
        }
    }

    private QueryResult result(
            String queryId, QueryStatus status, List<Map<String, Object>> rows, String reason, long startedAt) {
        QueryResult result = new QueryResult();
        result.setQueryId(queryId);
        result.setStatus(status);
        result.setRows(rows);
        result.setRowCount(rows.size());
        result.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
        result.setReason(reason);
        if (!rows.isEmpty()) {
            result.setColumns(List.copyOf(rows.getFirst().keySet()));
        }
        return result;
    }

    private QueryAuditRecord auditRecord(
            String userSubject, QueryRequest request, String queryId, int parameterCount, QueryResult result) {
        QueryAuditRecord record = new QueryAuditRecord();
        record.setQueryId(queryId);
        record.setWorkspaceId(request.getWorkspaceId());
        record.setUserSubject(userSubject);
        record.setAnalysisTaskId(request.getAnalysisTaskId());
        record.setRunId(request.getRunId());
        record.setSql(request.getSql());
        record.setParameterCount(parameterCount);
        record.setStatus(result.getStatus());
        record.setReason(result.getReason());
        record.setDurationMs(result.getDurationMs());
        record.setRowCount(result.getRowCount());
        return record;
    }
}
