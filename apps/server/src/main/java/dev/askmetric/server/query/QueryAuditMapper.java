package dev.askmetric.server.query;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 查询治理审计记录的持久化接口。 */
@Mapper
public interface QueryAuditMapper {
    /** 保存一次查询请求及其治理结果。 */
    @Insert("""
            insert into query_audit (
                query_id, workspace_id, user_subject, analysis_task_id, run_id,
                sql_text, parameter_count, status, rejection_reason,
                duration_ms, row_count
            ) values (
                #{queryId}, #{workspaceId}, #{userSubject}, #{analysisTaskId}, #{runId},
                #{sql}, #{parameterCount}, #{status}, #{reason},
                #{durationMs}, #{rowCount}
            )
            """)
    int insert(QueryAuditRecord record);
}
