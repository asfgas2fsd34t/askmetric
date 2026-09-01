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

@Mapper
public interface AnalysisTaskMapper {
    @ResultMap("analysisTask")
    @Select("""
            select task.analysis_task_id, task.conversation_id, task.goal,
                   task.status, task.source_agent_run_id, task.created_at
            from analysis_task task
            join workspace_membership membership on membership.workspace_id = task.workspace_id
            where task.conversation_id = #{conversationId}
              and task.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
              and task.status in ('ACTIVE', 'WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL')
            order by task.created_at, task.analysis_task_id
            limit 1
            """)
    Optional<AnalysisTask> findOpen(
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

    @Results(id = "analysisTask", value = {
            @Result(column = "analysis_task_id", property = "analysisTaskId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "source_agent_run_id", property = "sourceAgentRunId"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select task.analysis_task_id, task.conversation_id, task.goal,
                   task.status, task.source_agent_run_id, task.created_at
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
