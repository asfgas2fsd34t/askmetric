package dev.askmetric.server.evidence;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** Evidence Snapshot 的工作区隔离持久化接口。 */
@Mapper
public interface EvidenceSnapshotMapper {
    /** 保存一条不可变证据快照；queryId 唯一约束保证同一次查询只生成一份证据。 */
    @Insert("""
            insert into evidence_snapshot (
                evidence_snapshot_id, workspace_id, analysis_task_id, run_id, query_id,
                source_table, source_range, columns_json, rows_json, row_count,
                duration_ms, snapshot_hash, created_at
            ) values (
                #{record.evidenceSnapshotId}, #{record.workspaceId}, #{record.analysisTaskId},
                #{record.runId}, #{record.queryId}, #{record.sourceTable}, #{record.sourceRange},
                #{record.columnsJson}, #{record.rowsJson}, #{record.rowCount}, #{record.durationMs},
                #{record.snapshotHash}, #{record.createdAt}
            )
            """)
    int insert(@Param("record") EvidenceSnapshotRecord record);

    /** 读取 Conversation 内、当前用户 Workspace 可见的证据快照。 */
    @Results(id = "evidenceSnapshot", value = {
            @Result(column = "evidence_snapshot_id", property = "evidenceSnapshotId"),
            @Result(column = "analysis_task_id", property = "analysisTaskId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "query_id", property = "queryId"),
            @Result(column = "source_table", property = "sourceTable"),
            @Result(column = "source_range", property = "sourceRange"),
            @Result(column = "columns_json", property = "columnsJson"),
            @Result(column = "rows_json", property = "rowsJson"),
            @Result(column = "row_count", property = "rowCount"),
            @Result(column = "duration_ms", property = "durationMs"),
            @Result(column = "snapshot_hash", property = "snapshotHash"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select snapshot.evidence_snapshot_id, task.conversation_id,
                   snapshot.analysis_task_id, snapshot.run_id, snapshot.query_id,
                   snapshot.source_table, snapshot.source_range, snapshot.columns_json,
                   snapshot.rows_json, snapshot.row_count, snapshot.duration_ms,
                   snapshot.snapshot_hash, snapshot.created_at
            from evidence_snapshot snapshot
            join analysis_task task on task.analysis_task_id = snapshot.analysis_task_id
            join workspace_membership membership on membership.workspace_id = snapshot.workspace_id
            where task.conversation_id = #{conversationId}
              and snapshot.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by snapshot.created_at, snapshot.evidence_snapshot_id
            """)
    List<EvidenceSnapshotRecord> list(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId);
}
