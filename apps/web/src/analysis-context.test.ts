import { describe, expect, it } from "vitest";

import {
  deriveStage,
  formatEvidenceCell,
  findingBadge,
  stageProgress,
} from "./analysis-context";
import type { AgentRun, AnalysisFinding, AnalysisTask } from "./conversation";

function task(overrides: Partial<AnalysisTask> = {}): AnalysisTask {
  return {
    analysisTaskId: "task-1",
    conversationId: "conv-1",
    goal: "为什么本月 MRR 下降？",
    status: "active",
    sourceAgentRunId: "run-1",
    metricDefinitionVersionId: null,
    createdAt: "2026-09-09T00:00:00Z",
    ...overrides,
  };
}

function run(events: Partial<AgentRun["auditEvents"][number]>[]): AgentRun {
  return {
    runId: "run-fixed",
    conversationId: "conv-1",
    inputMessageId: "message-1",
    intentRoute: "analysis",
    intentConfidence: 0.95,
    analysisTaskId: "task-1",
    metricDefinitionVersionId: null,
    createdAt: "2026-09-09T00:00:00Z",
    auditEvents: events.map((event, index) => ({
      eventId: "event-" + index,
      runId: "run-1",
      sequence: index + 1,
      eventType: "agent.run.progress",
      occurredAt: "2026-09-09T00:00:00Z",
      message: "",
      source: "java",
      ...event,
    })),
  };
}

describe("deriveStage", () => {
  it("stays idle without an analysis task", () => {
    expect(deriveStage(undefined, [])).toMatchObject({ key: "idle", waiting: false });
  });

  it("pushes to clarification and waits for the user on caliber events", () => {
    const stage = deriveStage(task({ status: "waiting_for_input" }), [
      run([
        { eventType: "agent.run.accepted", message: "分析 Agent 运行已接受" },
        { eventType: "agent.run.clarification", message: "阶段 clarification：等待业务用户确认口径" },
      ]),
    ]);
    expect(stage.key).toBe("clarification");
    expect(stage.waiting).toBe(true);
  });

  it("advances to planning on plan events", () => {
    const stage = deriveStage(task({ metricDefinitionVersionId: "metric_definition_mrr_v3" }), [
      run([
        { eventType: "agent.run.accepted", message: "分析 Agent 运行已接受" },
        { eventType: "agent.run.clarification", message: "阶段 clarification：…" },
      ]),
      run([{ eventType: "agent.run.plan", message: "阶段 planning：分析计划：1. …" }]),
    ]);
    expect(stage.key).toBe("planning");
    expect(stage.waiting).toBe(false);
  });

  it("enters retrieving on stage progress and synthesizing on findings", () => {
    const retrieving = deriveStage(task(), [
      run([
        { eventType: "agent.run.accepted", message: "分析 Agent 运行已接受" },
        { eventType: "agent.run.progress", message: "阶段 retrieving：通过 Java Query Gateway 检索受治理证据" },
      ]),
    ]);
    expect(retrieving.key).toBe("retrieving");

    const synthesizing = deriveStage(task(), [
      run([
        { eventType: "agent.run.accepted", message: "分析 Agent 运行已接受" },
        { eventType: "agent.run.progress", message: "阶段 retrieving：…" },
        { eventType: "agent.run.finding", message: "已验证：…" },
      ]),
    ]);
    expect(synthesizing.key).toBe("synthesizing");
  });

  it("settles on terminal states and marks waiting tasks", () => {
    const completed = deriveStage(task({ status: "active" }), [
      run([
        { eventType: "agent.run.accepted", message: "分析 Agent 运行已接受" },
        { eventType: "agent.run.plan", message: "阶段 planning：…" },
        { eventType: "agent.run.completed", message: "分析计划已展示" },
      ]),
    ]);
    expect(completed.key).toBe("completed");

    const failed = deriveStage(task({ status: "failed" }), [
      run([{ eventType: "agent.run.failed", message: "Agent Run 处理失败" }]),
    ]);
    expect(failed.key).toBe("failed");

    const waiting = deriveStage(task({ status: "waiting_for_input" }), [
      run([{ eventType: "agent.run.accepted", message: "分析 Agent 运行已接受" }]),
    ]);
    expect(waiting.waiting).toBe(true);
  });
});

describe("stageProgress", () => {
  it("marks the five rows done/active/pending by the current stage", () => {
    const clarification = stageProgress({ key: "clarification", label: "", waiting: false, progressKey: "clarification" });
    expect(clarification.map((row) => row.state)).toEqual([
      "active", "pending", "pending", "pending", "pending",
    ]);

    const retrieving = stageProgress({ key: "retrieving", label: "", waiting: false, progressKey: "retrieving" });
    expect(retrieving.map((row) => row.state)).toEqual([
      "done", "done", "active", "pending", "pending",
    ]);

    const completed = stageProgress({ key: "completed", label: "", waiting: false, progressKey: "completed" });
    expect(completed.map((row) => row.state)).toEqual([
      "done", "done", "done", "done", "active",
    ]);
  });

  it("freezes failure and cancellation at the last active stage", () => {
    const failedAtRetrieving = stageProgress({
      key: "failed", label: "", waiting: false, progressKey: "retrieving",
    });
    expect(failedAtRetrieving.map((row) => row.state)).toEqual([
      "done", "done", "active", "pending", "pending",
    ]);

    const cancelledAtPlanning = stageProgress({
      key: "cancelled", label: "", waiting: false, progressKey: "planning",
    });
    expect(cancelledAtPlanning.map((row) => row.state)).toEqual([
      "done", "active", "pending", "pending", "pending",
    ]);
  });
});

describe("formatEvidenceCell", () => {
  it("formats numbers with separators and semantic tones", () => {
    expect(formatEvidenceCell(300000)).toEqual({ text: "300,000", tone: "positive" });
    expect(formatEvidenceCell(-150000)).toEqual({ text: "-150,000", tone: "negative" });
    expect(formatEvidenceCell(0)).toEqual({ text: "0", tone: "neutral" });
    expect(formatEvidenceCell("2025-06-01")).toEqual({ text: "2025-06-01", tone: "neutral" });
    expect(formatEvidenceCell("ENTERPRISE")).toEqual({ text: "ENTERPRISE", tone: "neutral" });
    expect(formatEvidenceCell(null)).toEqual({ text: "空", tone: "neutral" });
  });
});

describe("findingBadge", () => {
  it("badges verified findings green and unverified amber", () => {
    expect(findingBadge({ verified: true })).toEqual({
      text: "已验证",
      tone: "verified",
    });
    expect(findingBadge({ verified: false })).toEqual({
      text: "证据不足",
      tone: "unverified",
    });
  });
});
