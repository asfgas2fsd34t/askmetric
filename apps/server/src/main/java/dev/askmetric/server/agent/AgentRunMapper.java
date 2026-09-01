package dev.askmetric.server.agent;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** Agent Run 及其审计事件的工作区隔离持久化查询。 */
@Mapper
public interface AgentRunMapper {
    /**
     * 为已授权用户在 Conversation 中创建运行。
     *
     * @return 插入行数；零表示 Conversation 不属于当前用户的 Workspace
     */
    @Insert("""
            insert into agent_run (
                run_id, workspace_id, conversation_id, input_message_id,
                intent_route, intent_confidence
            )
            select #{runId}, conversation.workspace_id, conversation.conversation_id, #{inputMessageId},
                   #{intentRoute}, #{intentConfidence}
            from conversation
            join workspace_membership membership
              on membership.workspace_id = conversation.workspace_id
            where conversation.conversation_id = #{conversationId}
              and conversation.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    int createRun(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("runId") String runId,
            @Param("inputMessageId") String inputMessageId,
            @Param("intentRoute") AgentRunIntentRoute intentRoute,
            @Param("intentConfidence") java.math.BigDecimal intentConfidence);

    /** 将 Analysis Task 关联到创建它的 Agent Run。 */
    @org.apache.ibatis.annotations.Update("""
            update agent_run run
            set analysis_task_id = #{analysisTaskId}
            where run.run_id = #{runId}
              and run.workspace_id = #{workspaceId}
              and exists (
                  select 1
                  from analysis_task task
                  join workspace_membership membership on membership.workspace_id = task.workspace_id
                  where task.analysis_task_id = #{analysisTaskId}
                    and task.source_agent_run_id = run.run_id
                    and task.workspace_id = run.workspace_id
                    and task.conversation_id = run.conversation_id
                    and membership.user_subject = #{userSubject}
              )
            """)
    int linkAnalysisTask(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId,
            @Param("analysisTaskId") String analysisTaskId);

    /** 追加一条不可变的 Agent Run 审计事件。 */
    @Insert("""
            insert into agent_run_event (
                event_id, run_id, sequence, event_type, message, source
            )
            select #{eventId}, run.run_id, #{sequence}, #{eventType}, #{message}, #{source}
            from agent_run run
            join workspace_membership membership on membership.workspace_id = run.workspace_id
            where run.run_id = #{runId}
              and run.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    int appendAuditEvent(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("eventId") String eventId,
            @Param("runId") String runId,
            @Param("sequence") long sequence,
            @Param("eventType") AgentRunEventType eventType,
            @Param("message") String message,
            @Param("source") AgentRunEventSource source);

    /** 读取 Conversation 内、当前用户有权查看的 Agent Run。 */
    @Results(id = "persistedAgentRun", value = {
            @Result(column = "run_id", property = "runId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "input_message_id", property = "inputMessageId"),
            @Result(column = "intent_route", property = "intentRoute"),
            @Result(column = "intent_confidence", property = "intentConfidence"),
            @Result(column = "analysis_task_id", property = "analysisTaskId"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select run.run_id, run.conversation_id, run.input_message_id,
                   run.intent_route, run.intent_confidence, run.analysis_task_id,
                   run.created_at
            from agent_run run
            join workspace_membership membership on membership.workspace_id = run.workspace_id
            where run.conversation_id = #{conversationId}
              and run.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by run.created_at, run.run_id
            """)
    List<PersistedAgentRun> runs(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId);

    /** 读取同一 Agent Run 内按序发生的审计事件。 */
    @Results(id = "agentRunAuditEvent", value = {
            @Result(column = "event_id", property = "eventId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "occurred_at", property = "occurredAt")
    })
    @Select("""
            select event.event_id, event.run_id, event.sequence, event.event_type,
                   event.occurred_at, event.message, event.source
            from agent_run_event event
            join agent_run run on run.run_id = event.run_id
            join workspace_membership membership on membership.workspace_id = run.workspace_id
            where event.run_id = #{runId}
              and run.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by event.sequence
            """)
    List<AgentRunAuditEvent> auditEvents(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId);
}
