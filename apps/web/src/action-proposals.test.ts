import { describe, expect, it } from "vitest";

import {
  proposalActionTypeLabel,
  proposalIsActionable,
  proposalStatusBadgeClass,
  proposalStatusLabel,
} from "./action-proposals";
import type { ActionProposal } from "./conversation";

function proposal(overrides: Partial<ActionProposal> = {}): ActionProposal {
  return {
    actionProposalId: "action_proposal_1",
    workspaceId: "workspace-demo",
    conversationId: "conversation_1",
    analysisTaskId: "analysis_task_1",
    sourceAgentRunId: "run_1",
    actionType: "PROMOTE_CUSTOM_CALIBER",
    status: "AWAITING_CONFIRMATION",
    metricKey: "mrr",
    versionLabel: "custom-1",
    calculationRule: "规则",
    timeBoundary: "月末",
    exclusions: "无",
    policyVersion: 1,
    idempotencyKey: "PROMOTE_CUSTOM_CALIBER:run_1",
    proposedBy: "alice",
    createdAt: "2026-09-13T00:00:00Z",
    ...overrides,
  };
}

describe("action proposal presentation", () => {
  it("labels statuses in human terms with distinct badges", () => {
    expect(proposalStatusLabel("AWAITING_CONFIRMATION")).toBe("待你确认创建");
    expect(proposalStatusLabel("AWAITING_APPROVAL")).toBe("等待审批");
    expect(proposalStatusLabel("SUPERSEDED")).toBe("已被新提案取代");
    expect(proposalStatusLabel("DISCARDED")).toBe("已放弃");
    expect(proposalStatusBadgeClass("AWAITING_APPROVAL")).toBe("knowledge-ready");
    expect(proposalStatusBadgeClass("SUPERSEDED")).toBe("proposal-closed");
  });

  it("labels the governed action types", () => {
    expect(proposalActionTypeLabel("PROMOTE_CUSTOM_CALIBER"))
      .toBe("升级自定义口径为共享版本");
    expect(proposalActionTypeLabel("REVISE_STANDARD_CALIBER")).toBe("修订标准口径");
  });

  it("only drafts awaiting the current initiator confirmation are actionable", () => {
    const draft = proposal();
    expect(proposalIsActionable(draft, "alice")).toBe(true);
    expect(proposalIsActionable(draft, "bob")).toBe(false);
    expect(proposalIsActionable(draft, undefined)).toBe(false);
    expect(proposalIsActionable(proposal({ status: "AWAITING_APPROVAL" }), "alice")).toBe(false);
    expect(proposalIsActionable(proposal({ status: "SUPERSEDED" }), "alice")).toBe(false);
    expect(proposalIsActionable(proposal({ status: "DISCARDED" }), "alice")).toBe(false);
  });
});
