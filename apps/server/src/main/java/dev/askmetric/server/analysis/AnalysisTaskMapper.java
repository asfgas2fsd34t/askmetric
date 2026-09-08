package dev.askmetric.server.analysis;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AnalysisTaskMapper {
    /** 读取当前用户 Workspace 内最新一个可继续的分析任务：活动任务优先，其次取等待口径确认的任务；被切换搁置的任务只能通过显式任务控制恢复。 */
    @ResultMap("analysisTask")
    @Select("""
            select task.analysis_task_id, task.conversation_id, task.goal,
                   task.status, task.source_agent_run_id, task.metric_definition_version_id,
                   task.created_at
            from analysis_task task
            join workspace_membership membership on membership.workspace_id = task.workspace_id
            where task.conversation_id = #{conversationId}
              and task.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
              and (
                  task.status = 'ACTIVE'
                  or (
                      task.status = 'WAITING_FOR_INPUT'
                      and not exists (
                          select 1
                          from analysis_task_event switched
                          where switched.analysis_task_id = task.analysis_task_id
                            and switched.event_type = 'SWITCHED'
                      )
                  )
              )
            order by (task.status = 'ACTIVE') desc, task.created_at desc, task.analysis_task_id desc
            limit 1
            """)
    Optional<AnalysisTask> findContinuable(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId);

    @Insert("""
            insert into analysis_task (
                analysis_task_id, workspace_id, conversation_id, goal, status, source_agent_run_id
            )
            select #{analysisTaskId}, run.workspace_id, run.conversation_id,
                   #{goal}, #{status}, run.run_id
            from agent_run run
            join workspace_membership membership on membership.workspace_id = run.workspace_id
            where run.run_id = #{sourceAgentRunId}
              and run.conversation_id = #{conversationId}
              and run.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    int create(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("analysisTaskId") String analysisTaskId,
            @Param("goal") String goal,
            @Param("status") AnalysisTaskStatus status,
            @Param("sourceAgentRunId") String sourceAgentRunId);

    /** 将任务置为等待输入（口径澄清或被新目标切换），已处于该状态时幂等。 */
    @Update("""
            update analysis_task task
            set status = 'WAITING_FOR_INPUT'
            where task.analysis_task_id = #{analysisTaskId}
              and task.conversation_id = #{conversationId}
              and task.workspace_id = #{workspaceId}
              and task.status in ('ACTIVE', 'WAITING_FOR_INPUT')
              and exists (
                  select 1
                  from workspace_membership membership
                  where membership.workspace_id = task.workspace_id
                    and membership.user_subject = #{userSubject}
              )
            """)
    int waitForInput(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("analysisTaskId") String analysisTaskId);

    /** 将等待输入的任务恢复为活动（例如业务用户确认口径后），已活动时幂等。 */
    @Update("""
            update analysis_task task
            set status = 'ACTIVE'
            where task.analysis_task_id = #{analysisTaskId}
              and task.conversation_id = #{conversationId}
              and task.workspace_id = #{workspaceId}
              and task.status in ('ACTIVE', 'WAITING_FOR_INPUT')
              and exists (
                  select 1
                  from workspace_membership membership
                  where membership.workspace_id = task.workspace_id
                    and membership.user_subject = #{userSubject}
              )
            """)
    int resume(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("analysisTaskId") String analysisTaskId);

    /** 为 Analysis Task 绑定同一 Workspace 中的不可变指标版本；用户调整口径时更新版本引用。 */
    @Update("""
            update analysis_task task
            set metric_definition_version_id = #{metricDefinitionVersionId}
            where task.analysis_task_id = #{analysisTaskId}
              and task.workspace_id = #{workspaceId}
              and exists (
                  select 1
                  from metric_definition_version definition
                  join workspace_membership membership
                    on membership.workspace_id = definition.workspace_id
                  where definition.metric_definition_version_id = #{metricDefinitionVersionId}
                    and definition.workspace_id = task.workspace_id
                    and membership.user_subject = #{userSubject}
              )
            """)
    int bindMetricDefinition(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("analysisTaskId") String analysisTaskId,
            @Param("metricDefinitionVersionId") String metricDefinitionVersionId);

    /** 记录由一个 Agent Run 触发的 Analysis Task 切换关系。 */
    @Insert("""
            insert into analysis_task_event (
                event_id, analysis_task_id, source_agent_run_id, event_type, related_analysis_task_id
            )
            select #{eventId}, previous.analysis_task_id, run.run_id, #{eventType}, next_task.analysis_task_id
            from analysis_task previous
            join analysis_task next_task
              on next_task.analysis_task_id = #{currentAnalysisTaskId}
             and next_task.workspace_id = previous.workspace_id
             and next_task.conversation_id = previous.conversation_id
            join agent_run run
              on run.run_id = #{sourceAgentRunId}
             and run.workspace_id = previous.workspace_id
             and run.conversation_id = previous.conversation_id
            join workspace_membership membership on membership.workspace_id = previous.workspace_id
            where previous.analysis_task_id = #{previousAnalysisTaskId}
              and previous.workspace_id = #{workspaceId}
              and previous.conversation_id = #{conversationId}
              and membership.user_subject = #{userSubject}
            """)
    int appendSwitchEvent(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("eventId") String eventId,
            @Param("sourceAgentRunId") String sourceAgentRunId,
            @Param("previousAnalysisTaskId") String previousAnalysisTaskId,
            @Param("currentAnalysisTaskId") String currentAnalysisTaskId,
            @Param("eventType") AnalysisTaskEventType eventType);

    @Results(id = "analysisTask", value = {
            @Result(column = "analysis_task_id", property = "analysisTaskId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "source_agent_run_id", property = "sourceAgentRunId"),
            @Result(column = "metric_definition_version_id", property = "metricDefinitionVersionId"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select task.analysis_task_id, task.conversation_id, task.goal,
                   task.status, task.source_agent_run_id, task.metric_definition_version_id,
                   task.created_at
            from analysis_task task
            join workspace_membership membership on membership.workspace_id = task.workspace_id
            where task.conversation_id = #{conversationId}
              and task.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by task.created_at, task.analysis_task_id
            """)
    List<AnalysisTask> tasks(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId);
}
