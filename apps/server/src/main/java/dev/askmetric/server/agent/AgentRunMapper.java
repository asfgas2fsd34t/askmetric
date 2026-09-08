package dev.askmetric.server.agent;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
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

    /** 判断运行是否已经到达不可继续推进的终态。 */
    @Select("""
            select coalesce((
                select event_type in ('COMPLETED', 'FAILED', 'CANCELLED')
                from agent_run_event
                where run_id = #{runId}
                order by sequence desc
                limit 1
            ), false)
            """)
    boolean isTerminal(@Param("runId") String runId);

    /** 读取当前用户 Workspace 内、已关联 Analysis Task 的分析型 Agent Run。 */
    @ResultMap("persistedAgentRun")
    @Select("""
            select run.run_id, run.conversation_id, run.input_message_id,
                   run.intent_route, run.intent_confidence, run.analysis_task_id,
                   run.metric_definition_version_id, run.created_at
            from agent_run run
            join analysis_task task on task.analysis_task_id = run.analysis_task_id
            join workspace_membership membership on membership.workspace_id = run.workspace_id
            join lateral (
                select event.event_type
                from agent_run_event event
                where event.run_id = run.run_id
                order by event.sequence desc
                limit 1
            ) latest on latest.event_type in ('ACCEPTED', 'PROGRESS')
            where run.run_id = #{runId}
              and run.workspace_id = #{workspaceId}
              and run.intent_route = 'ANALYSIS'
              and task.workspace_id = run.workspace_id
              and task.conversation_id = run.conversation_id
              and task.status in ('ACTIVE', 'WAITING_FOR_INPUT')
              and membership.user_subject = #{userSubject}
            """)
    Optional<PersistedAgentRun> findAnalysisRun(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId);

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

    /** 将已确认且属于同一 Workspace 的指标版本绑定到本次 Agent Run。 */
    @org.apache.ibatis.annotations.Update("""
            update agent_run run
            set metric_definition_version_id = #{metricDefinitionVersionId}
            where run.run_id = #{runId}
              and run.workspace_id = #{workspaceId}
              and run.analysis_task_id = #{analysisTaskId}
              and run.metric_definition_version_id is null
              and exists (
                  select 1
                  from metric_definition_version definition
                  join workspace_membership membership
                    on membership.workspace_id = definition.workspace_id
                  where definition.metric_definition_version_id = #{metricDefinitionVersionId}
                    and definition.workspace_id = run.workspace_id
                    and membership.user_subject = #{userSubject}
              )
            """)
    int bindMetricDefinition(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId,
            @Param("analysisTaskId") String analysisTaskId,
            @Param("metricDefinitionVersionId") String metricDefinitionVersionId);

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
            with locked_run as (
                select run.run_id
                from agent_run run
                where run.run_id = #{runId}
                  and run.conversation_id = #{conversationId}
                for update
            ), latest as (
                select previous.sequence, previous.event_type
                from agent_run_event previous
                join locked_run run on run.run_id = previous.run_id
                order by previous.sequence desc
                limit 1
            )
            insert into agent_run_event (
                event_id, run_id, sequence, event_type, occurred_at, message, source
            )
            select #{eventId}, run.run_id, #{sequence}, #{eventType}, #{occurredAt}, #{message}, #{source}
            from locked_run run
            join latest on true
            where #{sequence} = latest.sequence + 1
              and (
                  (latest.event_type = 'ACCEPTED' and #{eventType} in ('PROGRESS', 'FAILED'))
                  or (latest.event_type = 'PROGRESS' and #{eventType} in ('PROGRESS', 'COMPLETED', 'FAILED'))
              )
            on conflict do nothing
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

    /**
     * 锁定运行与关联任务，将二者原子地取消，并返回用于 SSE 的终态事件。
     * 已终态或没有 Analysis Task 的运行不会发生变化。
     */
    @ResultMap("agentRunEvent")
    @Select("""
            with cancellable as (
                select run.run_id, run.conversation_id, task.analysis_task_id,
                       latest.sequence, latest.message
                from agent_run run
                join analysis_task task on task.analysis_task_id = run.analysis_task_id
                join workspace_membership membership on membership.workspace_id = run.workspace_id
                join lateral (
                    select event.sequence, event.event_type, event.message
                    from agent_run_event event
                    where event.run_id = run.run_id
                    order by event.sequence desc
                    limit 1
                ) latest on true
                where run.run_id = #{runId}
                  and run.conversation_id = #{conversationId}
                  and run.workspace_id = #{workspaceId}
                  and membership.user_subject = #{userSubject}
                  and latest.event_type in ('ACCEPTED', 'PROGRESS')
                  and task.status in ('ACTIVE', 'WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL')
                for update of run, task
            ), cancelled_event as (
                insert into agent_run_event (
                    event_id, run_id, sequence, event_type, message, source
                )
                select #{eventId}, run_id, sequence + 1, 'CANCELLED',
                       concat('分析已在“', left(message, 3000), '”阶段取消：', #{reason}),
                       'JAVA'
                from cancellable
                returning event_id, run_id, sequence, event_type, occurred_at, message, source
            ), cancelled_task as (
                update analysis_task task
                set status = 'CANCELLED'
                from cancellable, cancelled_event
                where task.analysis_task_id = cancellable.analysis_task_id
                returning task.analysis_task_id
            )
            select event.event_id, 1 as schema_version, event.run_id, event.sequence,
                   event.event_type, event.occurred_at, cancellable.conversation_id,
                   event.message, event.source
            from cancelled_event event
            join cancellable on cancellable.run_id = event.run_id
            join cancelled_task task on task.analysis_task_id = cancellable.analysis_task_id
            """)
    Optional<AgentRunEvent> cancelRun(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("runId") String runId,
            @Param("eventId") String eventId,
            @Param("reason") String reason);

    /** 读取 Agent Run 的完整事件，用于 SSE 连接建立或应用重启后的恢复。 */
    @Results(id = "agentRunEvent", value = {
            @Result(column = "event_id", property = "eventId"),
            @Result(column = "schema_version", property = "schemaVersion"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "sequence", property = "sequence"),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "occurred_at", property = "occurredAt"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "message", property = "message"),
            @Result(column = "source", property = "source")
    })
    @Select("""
            select event.event_id, 1 as schema_version, event.run_id, event.sequence, event.event_type,
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
            @Result(column = "metric_definition_version_id", property = "metricDefinitionVersionId"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select run.run_id, run.conversation_id, run.input_message_id,
                   run.intent_route, run.intent_confidence, run.analysis_task_id,
                   run.metric_definition_version_id,
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
