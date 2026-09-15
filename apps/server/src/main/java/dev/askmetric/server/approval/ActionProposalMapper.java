package dev.askmetric.server.approval;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 操作提案的持久化查询；参数列只出现在 INSERT，不存在任何原地修改参数的语句。 */
@Mapper
public interface ActionProposalMapper {
    @Results(id = "actionProposal", value = {
            @Result(column = "action_proposal_id", property = "actionProposalId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "analysis_task_id", property = "analysisTaskId"),
            @Result(column = "source_agent_run_id", property = "sourceAgentRunId"),
            @Result(column = "action_type", property = "actionType"),
            @Result(column = "status", property = "status"),
            @Result(column = "metric_key", property = "metricKey"),
            @Result(column = "version_label", property = "versionLabel"),
            @Result(column = "calculation_rule", property = "calculationRule"),
            @Result(column = "time_boundary", property = "timeBoundary"),
            @Result(column = "exclusions", property = "exclusions"),
            @Result(column = "policy_version", property = "policyVersion"),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "proposed_by", property = "proposedBy"),
            @Result(column = "confirmed_at", property = "confirmedAt"),
            @Result(column = "superseded_by", property = "supersededBy"),
            @Result(column = "decided_by", property = "decidedBy"),
            @Result(column = "decided_at", property = "decidedAt"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select proposal.*
            from action_proposal proposal
            join workspace_membership membership on membership.workspace_id = proposal.workspace_id
            where proposal.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
              and proposal.action_proposal_id = #{actionProposalId}
            """)
    Optional<ActionProposal> find(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("actionProposalId") String actionProposalId);

    /** 读取一个 Conversation 内的全部提案，供快照展示与审批前检查。 */
    @Select("""
            select proposal.*
            from action_proposal proposal
            join workspace_membership membership on membership.workspace_id = proposal.workspace_id
            where proposal.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
              and proposal.conversation_id = #{conversationId}
            order by proposal.created_at desc
            """)
    @ResultMap("actionProposal")
    List<ActionProposal> list(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId);

    /** 按幂等键读取已有提案；重复提交同一运行与操作类型时重放首次结果。 */
    @Select("""
            select *
            from action_proposal
            where workspace_id = #{workspaceId}
              and idempotency_key = #{idempotencyKey}
            """)
    @ResultMap("actionProposal")
    Optional<ActionProposal> findByIdempotencyKey(
            @Param("workspaceId") String workspaceId,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 从一次 Agent Run 派生提案：参数、策略版本与发起人全部由服务端从受治理状态推导，
     * 调用方不能自报任何口径内容；只有绑定了自定义口径的运行可以通过守卫。
     */
    @Insert("""
            insert into action_proposal (
                action_proposal_id, workspace_id, conversation_id, analysis_task_id,
                source_agent_run_id, action_type, status,
                metric_key, version_label, calculation_rule, time_boundary, exclusions,
                policy_version, idempotency_key, proposed_by
            )
            select #{actionProposalId}, run.workspace_id, run.conversation_id, run.analysis_task_id,
                   run.run_id, #{actionType}, 'AWAITING_CONFIRMATION',
                   definition.metric_key, definition.version_label,
                   definition.calculation_rule, definition.time_boundary, definition.exclusions,
                   policy.policy_version, #{idempotencyKey}, message.author_subject
            from agent_run run
            join analysis_task task on task.analysis_task_id = run.analysis_task_id
            join workspace_policy policy on policy.workspace_id = run.workspace_id
            join metric_definition_version definition
                   on definition.metric_definition_version_id = run.metric_definition_version_id
            join conversation_message message on message.message_id = run.input_message_id
            where run.run_id = #{runId}
              and run.analysis_task_id is not null
              and run.metric_definition_version_id is not null
              and definition.definition_source = 'CUSTOM'
            on conflict (workspace_id, idempotency_key) do nothing
            """)
    int insertFromRun(
            @Param("actionProposalId") String actionProposalId,
            @Param("runId") String runId,
            @Param("actionType") String actionType,
            @Param("idempotencyKey") String idempotencyKey);

    /** 发起者显式确认提案创建；参数与幂等键保持不变，只有状态和确认时间前进。 */
    @Update("""
            update action_proposal
            set status = 'AWAITING_APPROVAL', confirmed_at = current_timestamp
            where action_proposal_id = #{actionProposalId}
              and workspace_id = #{workspaceId}
              and proposed_by = #{userSubject}
              and status = 'AWAITING_CONFIRMATION'
            """)
    int confirm(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("actionProposalId") String actionProposalId);

    /** 发起者放弃待确认提案；已确认或已取代的提案不能被放弃。 */
    @Update("""
            update action_proposal
            set status = 'DISCARDED'
            where action_proposal_id = #{actionProposalId}
              and workspace_id = #{workspaceId}
              and proposed_by = #{userSubject}
              and status = 'AWAITING_CONFIRMATION'
            """)
    int discard(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("actionProposalId") String actionProposalId);

    /**
     * 同一分析任务出现新提案时，旧的待确认/待审批提案整体让位；参数不被触碰。
     * 只取代创建时间严格更早的提案，避免并发插入时两个新提案互相取代导致全部失效。
     */
    @Update("""
            update action_proposal
            set status = 'SUPERSEDED', superseded_by = #{newActionProposalId}
            where analysis_task_id = (
                    select analysis_task_id from action_proposal where action_proposal_id = #{newActionProposalId}
                  )
              and action_proposal_id <> #{newActionProposalId}
              and status in ('AWAITING_CONFIRMATION', 'AWAITING_APPROVAL')
              and created_at < (
                    select created_at from action_proposal where action_proposal_id = #{newActionProposalId}
                  )
            """)
    int supersedeActiveForTask(@Param("newActionProposalId") String newActionProposalId);

    /**
     * 有权限成员批准提案：单条原子 UPDATE 同时校验审批权限、待审批状态、
     * 策略版本未过期与分离审批规则；任何守卫不过即零行更新。
     */
    @Update("""
            update action_proposal proposal
            set status = 'APPROVED', decided_by = #{userSubject}, decided_at = current_timestamp
            from workspace_membership membership, workspace_policy policy
            where proposal.action_proposal_id = #{actionProposalId}
              and proposal.workspace_id = #{workspaceId}
              and membership.workspace_id = proposal.workspace_id
              and membership.user_subject = #{userSubject}
              and membership.permissions @> ARRAY['APPROVE_ACTION_PROPOSAL']
              and policy.workspace_id = proposal.workspace_id
              and policy.policy_version = proposal.policy_version
              and not (policy.requires_separate_approver and proposal.proposed_by = #{userSubject})
              and proposal.status = 'AWAITING_APPROVAL'
            """)
    int approve(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("actionProposalId") String actionProposalId);

    /** 有权限成员拒绝提案；守卫与 approve 相同，拒绝是不可逆终态。 */
    @Update("""
            update action_proposal proposal
            set status = 'REJECTED', decided_by = #{userSubject}, decided_at = current_timestamp
            from workspace_membership membership, workspace_policy policy
            where proposal.action_proposal_id = #{actionProposalId}
              and proposal.workspace_id = #{workspaceId}
              and membership.workspace_id = proposal.workspace_id
              and membership.user_subject = #{userSubject}
              and membership.permissions @> ARRAY['APPROVE_ACTION_PROPOSAL']
              and policy.workspace_id = proposal.workspace_id
              and policy.policy_version = proposal.policy_version
              and not (policy.requires_separate_approver and proposal.proposed_by = #{userSubject})
              and proposal.status = 'AWAITING_APPROVAL'
            """)
    int reject(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("actionProposalId") String actionProposalId);

    /** 审批队列：读取工作区内指定状态的提案（含成员直建），供审批人发现待办。 */
    @ResultMap("actionProposal")
    @Select("""
            select proposal.*
            from action_proposal proposal
            join workspace_membership membership on membership.workspace_id = proposal.workspace_id
            where proposal.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
              and proposal.status = #{status}
            order by proposal.created_at, proposal.action_proposal_id
            """)
    List<ActionProposal> listByStatus(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("status") String status);

    /**
     * 有审批权限的成员直接创建标准口径修订提案：参数由服务端校验后快照落库，
     * 状态直达等待审批；不关联任何对话、任务或运行。
     */
    @Insert("""
            insert into action_proposal (
                action_proposal_id, workspace_id, action_type, status,
                metric_key, version_label, calculation_rule, time_boundary, exclusions,
                policy_version, idempotency_key, proposed_by, confirmed_at
            )
            select #{actionProposalId}, membership.workspace_id, #{actionType}, 'AWAITING_APPROVAL',
                   #{metricKey}, #{versionLabel}, #{calculationRule}, #{timeBoundary}, #{exclusions},
                   policy.policy_version, #{idempotencyKey}, #{userSubject}, current_timestamp
            from workspace_membership membership
            join workspace_policy policy on policy.workspace_id = membership.workspace_id
            where membership.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
              and membership.permissions @> ARRAY['APPROVE_ACTION_PROPOSAL']
            on conflict (workspace_id, idempotency_key) do nothing
            """)
    int insertFromMember(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("actionProposalId") String actionProposalId,
            @Param("actionType") String actionType,
            @Param("metricKey") String metricKey,
            @Param("versionLabel") String versionLabel,
            @Param("calculationRule") String calculationRule,
            @Param("timeBoundary") String timeBoundary,
            @Param("exclusions") String exclusions,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 同一指标的活跃修订提案被新提案取代；参数不被触碰。
     * 只处理成员直建（无分析任务）的提案，运行派生提案按任务取代。
     */
    @Update("""
            update action_proposal
            set status = 'SUPERSEDED', superseded_by = #{newActionProposalId}
            where workspace_id = #{workspaceId}
              and metric_key = #{metricKey}
              and action_type = 'REVISE_STANDARD_CALIBER'
              and analysis_task_id is null
              and action_proposal_id <> #{newActionProposalId}
              and status in ('AWAITING_CONFIRMATION', 'AWAITING_APPROVAL')
              and created_at < (
                    select created_at from action_proposal where action_proposal_id = #{newActionProposalId}
                  )
            """)
    int supersedeActiveMemberRevisions(
            @Param("workspaceId") String workspaceId,
            @Param("metricKey") String metricKey,
            @Param("newActionProposalId") String newActionProposalId);

    /** 读取提案创建时策略版本的当前值与分离审批要求，用于失败原因分类。 */
    @Select("""
            select policy.policy_version, policy.requires_separate_approver
            from workspace_policy policy
            where policy.workspace_id = #{workspaceId}
            """)
    Map<String, Object> currentPolicy(@Param("workspaceId") String workspaceId);
}
