package dev.askmetric.server.approval;

import dev.askmetric.server.agent.AgentRunContext;
import dev.askmetric.server.agent.AgentRunMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作提案生命周期：Agent 从运行派生待确认草案 → 发起者显式确认 → 等待审批（T23）。
 * 参数在创建时从受治理状态快照而来，此后任何路径都只推进状态，不存在参数修改。
 */
@Service
public class ActionProposalService {
    /** ADR-0009：业务用户与 Agent 目前只能创建升级提案；修订标准口径的创建路径属于后续票。 */
    private static final ActionProposalType AGENT_CREATABLE_TYPE = ActionProposalType.PROMOTE_CUSTOM_CALIBER;

    private final ActionProposalMapper mapper;
    private final AgentRunMapper agentRunMapper;

    public ActionProposalService(ActionProposalMapper mapper, AgentRunMapper agentRunMapper) {
        this.mapper = mapper;
        this.agentRunMapper = agentRunMapper;
    }

    /**
     * 以一次 Agent Run 为来源创建操作提案草案。
     * 幂等键由服务端按（操作类型、运行）确定性派生，消息重放不会产生第二个提案。
     */
    @Transactional
    public ActionProposal createFromRun(String runId, String actionType) {
        if (actionType == null || !AGENT_CREATABLE_TYPE.name().equals(actionType.strip())) {
            throw new IllegalArgumentException("Agent 只能提交 " + AGENT_CREATABLE_TYPE + " 提案");
        }
        AgentRunContext context = agentRunMapper.findRunContext(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent Run not found: " + runId));
        String idempotencyKey = AGENT_CREATABLE_TYPE.name() + ":" + runId;
        String actionProposalId = "action_proposal_" + UUID.randomUUID();
        if (mapper.insertFromRun(actionProposalId, runId, AGENT_CREATABLE_TYPE.name(), idempotencyKey) != 1) {
            // 冲突只可能来自同一（运行、操作类型）的重复提交：重放首次提案而不是报错。
            return mapper.findByIdempotencyKey(context.getWorkspaceId(), idempotencyKey)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "只有绑定了自定义口径的分析运行可以提出升级提案"));
        }
        // 新提案让同一分析任务的旧提案整体失效，体现“修改即新提案”。
        mapper.supersedeActiveForTask(actionProposalId);
        return find(context.getAuthorSubject(), context.getWorkspaceId(), actionProposalId);
    }

    /** 发起者确认提案创建；提案自此进入等待审批并保持参数不可变。 */
    @Transactional
    public ActionProposal confirm(String userSubject, String workspaceId, String actionProposalId) {
        if (mapper.confirm(userSubject, workspaceId, actionProposalId) != 1) {
            throw new IllegalArgumentException("提案不是待确认状态，或当前成员不是发起者");
        }
        return find(userSubject, workspaceId, actionProposalId);
    }

    /** 发起者放弃提案；放弃的提案不会产生任何副作用。 */
    @Transactional
    public ActionProposal discard(String userSubject, String workspaceId, String actionProposalId) {
        if (mapper.discard(userSubject, workspaceId, actionProposalId) != 1) {
            throw new IllegalArgumentException("提案不是待确认状态，或当前成员不是发起者");
        }
        return find(userSubject, workspaceId, actionProposalId);
    }

    @Transactional(readOnly = true)
    public List<ActionProposal> list(String userSubject, String workspaceId, String conversationId) {
        return mapper.list(userSubject, workspaceId, conversationId);
    }

    private ActionProposal find(String userSubject, String workspaceId, String actionProposalId) {
        // 成员视角校验归属，工作区外的成员无法读取本工作区的提案。
        return mapper.find(userSubject, workspaceId, actionProposalId)
                .orElseThrow(() -> new IllegalStateException("Action Proposal was not persisted"));
    }
}
