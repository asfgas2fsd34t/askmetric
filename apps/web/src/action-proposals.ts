import type { ActionProposal } from "./conversation";

/** 操作提案的展示词汇；状态只随人工动作推进，Agent 没有执行路径。 */
type ProposalStatus = ActionProposal["status"];

const STATUS_LABELS: Record<ProposalStatus, string> = {
  AWAITING_CONFIRMATION: "待你确认创建",
  AWAITING_APPROVAL: "等待审批",
  SUPERSEDED: "已被新提案取代",
  DISCARDED: "已放弃",
};

const STATUS_BADGE_CLASSES: Record<ProposalStatus, string> = {
  AWAITING_CONFIRMATION: "knowledge-uploaded",
  AWAITING_APPROVAL: "knowledge-ready",
  SUPERSEDED: "proposal-closed",
  DISCARDED: "proposal-closed",
};

const ACTION_TYPE_LABELS: Record<ActionProposal["actionType"], string> = {
  PROMOTE_CUSTOM_CALIBER: "升级自定义口径为共享版本",
  REVISE_STANDARD_CALIBER: "修订标准口径",
};

export function proposalStatusLabel(status: ActionProposal["status"]): string {
  return STATUS_LABELS[status] ?? String(status);
}

export function proposalStatusBadgeClass(status: ActionProposal["status"]): string {
  return STATUS_BADGE_CLASSES[status] ?? "proposal-closed";
}

export function proposalActionTypeLabel(actionType: ActionProposal["actionType"]): string {
  return ACTION_TYPE_LABELS[actionType] ?? String(actionType);
}

/** 只有待确认草案能被发起者确认或放弃；其余状态一律只读展示。 */
export function proposalIsActionable(
  proposal: ActionProposal,
  currentUserId: string | undefined,
): boolean {
  return proposal.status === "AWAITING_CONFIRMATION"
    && !!currentUserId
    && proposal.proposedBy === currentUserId;
}
