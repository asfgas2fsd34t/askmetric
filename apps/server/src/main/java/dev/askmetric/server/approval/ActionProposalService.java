package dev.askmetric.server.approval;

import dev.askmetric.server.agent.AgentRunContext;
import dev.askmetric.server.agent.AgentRunMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

    /** 有权限成员批准提案；守卫失败时按可区分的原因拒绝，不产生部分状态。 */
    @Transactional
    public ActionProposal approve(String userSubject, String workspaceId, String actionProposalId) {
        return decide(userSubject, workspaceId, actionProposalId, true);
    }

    /** 有权限成员拒绝提案；拒绝是不可逆终态，不会产生任何副作用。 */
    @Transactional
    public ActionProposal reject(String userSubject, String workspaceId, String actionProposalId) {
        return decide(userSubject, workspaceId, actionProposalId, false);
    }

    private ActionProposal decide(String userSubject, String workspaceId, String actionProposalId, boolean approve) {
        ActionProposal proposal = mapper.find(userSubject, workspaceId, actionProposalId)
                .orElseThrow(() -> new IllegalArgumentException("提案不存在或不属于当前工作区"));
        if (proposal.getStatus() != ActionProposalStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException(
                    "提案当前状态是 " + proposal.getStatus() + "，不可审批；只有等待审批的提案可以被批准或拒绝");
        }
        Map<String, Object> policy = mapper.currentPolicy(workspaceId);
        int currentVersion = ((Number) policy.get("policy_version")).intValue();
        if (currentVersion != proposal.getPolicyVersion()) {
            throw new IllegalArgumentException(
                    "工作区策略版本已从 " + proposal.getPolicyVersion() + " 变为 " + currentVersion
                            + "，提案过期，需要以新提案重新发起");
        }
        if (Boolean.TRUE.equals(policy.get("requires_separate_approver"))
                && proposal.getProposedBy().equals(userSubject)) {
            throw new IllegalArgumentException("当前策略要求分离审批，发起者不能批准或拒绝自己的提案");
        }
        int updated = approve
                ? mapper.approve(userSubject, workspaceId, actionProposalId)
                : mapper.reject(userSubject, workspaceId, actionProposalId);
        if (updated != 1) {
            throw new IllegalArgumentException("提案审批未生效：权限、状态或策略守卫未通过");
        }
        return find(userSubject, workspaceId, actionProposalId);
    }

    /**
     * 有审批权限的成员直接创建标准口径修订提案（ADR-0009 自上而下路径）。
     * 幂等键按参数内容派生：相同参数重复提交重放首提案，改参数即新提案。
     */
    @Transactional
    public ActionProposal createRevisionFromMember(
            String userSubject, String workspaceId, CreateRevisionRequest request) {
        String metricKey = requiredText(request == null ? null : request.getMetricKey(), "metricKey", 100);
        String versionLabel = requiredText(request == null ? null : request.getVersionLabel(), "versionLabel", 100);
        String calculationRule = requiredText(request == null ? null : request.getCalculationRule(), "calculationRule", 2000);
        String timeBoundary = requiredText(request == null ? null : request.getTimeBoundary(), "timeBoundary", 2000);
        String exclusions = requiredText(request == null ? null : request.getExclusions(), "exclusions", 2000);
        // 长度前缀拼接避免用户文本含换行时不同参数组合得到同一幂等键。
        String canonical = String.valueOf(workspaceId.length()) + ":" + workspaceId
                + String.valueOf(metricKey.length()) + ":" + metricKey
                + String.valueOf(versionLabel.length()) + ":" + versionLabel
                + String.valueOf(calculationRule.length()) + ":" + calculationRule
                + String.valueOf(timeBoundary.length()) + ":" + timeBoundary
                + String.valueOf(exclusions.length()) + ":" + exclusions;
        String idempotencyKey = "REVISE:" + sha256(canonical);
        String actionProposalId = "action_proposal_" + UUID.randomUUID();
        if (mapper.insertFromMember(
                userSubject, workspaceId, actionProposalId,
                ActionProposalType.REVISE_STANDARD_CALIBER.name(),
                metricKey, versionLabel, calculationRule, timeBoundary, exclusions,
                idempotencyKey) != 1) {
            return mapper.findByIdempotencyKey(workspaceId, idempotencyKey)
                    .orElseThrow(() -> new IllegalArgumentException("当前成员没有创建修订提案的权限"));
        }
        mapper.supersedeActiveMemberRevisions(workspaceId, metricKey, actionProposalId);
        return find(userSubject, workspaceId, actionProposalId);
    }

    /** 审批队列：等待审批的提案按创建时间排列。 */
    @Transactional(readOnly = true)
    public List<ActionProposal> listAwaitingApproval(String userSubject, String workspaceId) {
        return mapper.listByStatus(userSubject, workspaceId, ActionProposalStatus.AWAITING_APPROVAL.name());
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** 成员直建修订提案的请求体。 */
    @lombok.Data
    public static class CreateRevisionRequest {
        private String metricKey;
        private String versionLabel;
        private String calculationRule;
        private String timeBoundary;
        private String exclusions;
    }
}
