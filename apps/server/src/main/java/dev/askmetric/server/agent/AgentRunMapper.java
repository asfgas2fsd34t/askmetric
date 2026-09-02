package dev.askmetric.server.agent;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** Agent Run 及其审计事件的工作区隔离持久化查询。 */
@Mapper
public interface AgentRunMapper {
    /** 查询数据库中的 Agent Run 是否属于指定 Conversation 和 Workspace。 */
    @Select("""
            select exists (
                select 1
                from agent_run
                where run_id = #{runId}
                  and workspace_id = #{workspaceId}
                  and conversation_id = #{conversationId}
            )
            """)
    boolean exists(
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("runId") String runId);

    /** 判断事件是否已经按 eventId 持久化。 */
    @Select("""
            select exists (
                select 1 from agent_run_event
                where event_id = #{eventId} and run_id = #{runId}
            )
            """)
    boolean eventExists(@Param("eventId") String eventId, @Param("runId") String runId);

    /** 判断同一 Agent Run 是否已经有该序号，用于识别迟到的旧事件。 */
    @Select("""
            select exists (
                select 1 from agent_run_event
                where run_id = #{runId} and sequence = #{sequence}
            )
            """)
    boolean sequenceExists(@Param("runId") String runId, @Param("sequence") long sequence);

    /** 获取内部事件所属 Workspace，用于重建 SSE 内存投影。 */
    @Select("""
            select workspace_id
            from agent_run
            where run_id = #{runId} and conversation_id = #{conversationId}
            """)
    Optional<String> workspaceId(
            @Param("conversationId") String conversationId, @Param("runId") String runId);

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

    /** 将同一 Conversation 内、当前用户有权访问的 Analysis Task 关联到 Agent Run。 */
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
            on conflict (event_id) do nothing
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

    /** 持久化已通过契约校验的 Python 事件；eventId 和 runId/sequence 都参与幂等约束。 */
    @Insert("""
            insert into agent_run_event (
                event_id, run_id, sequence, event_type, occurred_at, message, source
            )
            select #{eventId}, run.run_id, #{sequence}, #{eventType}, #{occurredAt}, #{message}, #{source}
            from agent_run run
            where run.run_id = #{runId}
              and run.conversation_id = #{conversationId}
              and #{sequence} = (
                  select coalesce(max(previous.sequence), 0) + 1
                  from agent_run_event previous
                  where previous.run_id = run.run_id
              )
              and exists (
                  select 1
                  from agent_run_event previous
                  where previous.run_id = run.run_id
                    and previous.sequence = (
                        select max(latest.sequence)
                        from agent_run_event latest
                        where latest.run_id = run.run_id
                    )
                    and (
                        (previous.event_type = 'ACCEPTED' and #{eventType} in ('PROGRESS', 'FAILED'))
                        or (previous.event_type = 'PROGRESS' and #{eventType} in ('PROGRESS', 'COMPLETED', 'FAILED'))
                    )
              )
            on conflict (event_id) do nothing
            """)
    int appendExternalEvent(
            @Param("eventId") String eventId,
            @Param("runId") String runId,
            @Param("sequence") long sequence,
            @Param("eventType") AgentRunEventType eventType,
            @Param("occurredAt") java.time.Instant occurredAt,
            @Param("conversationId") String conversationId,
            @Param("message") String message,
            @Param("source") AgentRunEventSource source);

    /** 读取 Agent Run 的完整事件，用于 SSE 连接建立或应用重启后的恢复。 */
    @Results(id = "agentRunEvent", value = {
            @Result(column = "event_id", property = "eventId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "sequence", property = "sequence"),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "occurred_at", property = "occurredAt"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "message", property = "message"),
            @Result(column = "source", property = "source")
    })
    @Select("""
            select event.event_id, event.run_id, event.sequence, event.event_type,
                   event.occurred_at, run.conversation_id, event.message, event.source
            from agent_run_event event
            join agent_run run on run.run_id = event.run_id
            where event.run_id = #{runId}
              and run.conversation_id = #{conversationId}
            order by event.sequence
            """)
    List<AgentRunEvent> eventsForRun(
            @Param("conversationId") String conversationId,
            @Param("runId") String runId);

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
