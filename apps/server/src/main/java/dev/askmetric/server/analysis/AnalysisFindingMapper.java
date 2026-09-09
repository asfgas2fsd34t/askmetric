package dev.askmetric.server.analysis;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AnalysisFindingMapper {
    /**
     * 持久化一条已验证发现；运行必须存在、口径已绑定同一指标版本，
     * 且引用的每个 Evidence Snapshot 都属于同一运行，否则不插入。
     */
    @Insert("""
            insert into analysis_finding (
                finding_id, workspace_id, analysis_task_id, run_id, metric_definition_version_id,
                verified, conclusion, evidence_snapshot_ids, assumptions, uncertainties
            )
            select #{findingId}, run.workspace_id, run.analysis_task_id, run.run_id,
                   run.metric_definition_version_id, #{verified}, #{conclusion},
                   #{evidenceSnapshotIdsJson}, #{assumptionsJson}, #{uncertaintiesJson}
            from agent_run run
            where run.run_id = #{runId}
              and run.intent_route = 'ANALYSIS'
              and run.analysis_task_id is not null
              and run.metric_definition_version_id = #{metricDefinitionVersionId}
              and (
                  select count(*)
                  from jsonb_array_elements_text(#{evidenceSnapshotIdsJson}::jsonb) as requested(id)
                  where not exists (
                      select 1
                      from evidence_snapshot snapshot
                      where snapshot.evidence_snapshot_id = requested.id
                        and snapshot.run_id = run.run_id
                        and snapshot.workspace_id = run.workspace_id
                  )
              ) = 0
            on conflict (finding_id) do nothing
            """)
    int insert(
            @Param("findingId") String findingId,
            @Param("runId") String runId,
            @Param("metricDefinitionVersionId") String metricDefinitionVersionId,
            @Param("verified") boolean verified,
            @Param("conclusion") String conclusion,
            @Param("evidenceSnapshotIdsJson") String evidenceSnapshotIdsJson,
            @Param("assumptionsJson") String assumptionsJson,
            @Param("uncertaintiesJson") String uncertaintiesJson);

    /** 读取会话内当前用户有权查看的已验证发现，含其任务所属会话标识。 */
    @Results(id = "analysisFinding", value = {
            @Result(column = "finding_id", property = "findingId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "analysis_task_id", property = "analysisTaskId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "metric_definition_version_id", property = "metricDefinitionVersionId"),
            @Result(column = "verified", property = "verified"),
            @Result(column = "conclusion", property = "conclusion"),
            @Result(column = "evidence_snapshot_ids", property = "evidenceSnapshotIdsJson"),
            @Result(column = "assumptions", property = "assumptionsJson"),
            @Result(column = "uncertainties", property = "uncertaintiesJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select finding.finding_id, task.conversation_id, finding.analysis_task_id,
                   finding.run_id, finding.metric_definition_version_id, finding.verified,
                   finding.conclusion, finding.evidence_snapshot_ids, finding.assumptions,
                   finding.uncertainties, finding.created_at
            from analysis_finding finding
            join analysis_task task on task.analysis_task_id = finding.analysis_task_id
            join workspace_membership membership on membership.workspace_id = finding.workspace_id
            where task.conversation_id = #{conversationId}
              and finding.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by finding.created_at, finding.finding_id
            """)
    List<AnalysisFindingRecord> list(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId);
}
