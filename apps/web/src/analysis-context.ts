import type { AgentRun, AnalysisFinding, AnalysisTask } from "./conversation";

/** 分析任务的交互阶段，对应 spec 的 idle → … → 终态状态机。 */
export type AnalysisStageKey =
  | "idle"
  | "clarification"
  | "planning"
  | "retrieving"
  | "synthesizing"
  | "completed"
  | "failed"
  | "cancelled";

export interface AnalysisStage {
  key: AnalysisStageKey;
  label: string;
  /** 是否在等待业务用户行动（确认口径、补充输入）。 */
  waiting: boolean;
  /** 阶段行推进使用的非终态阶段；终态时冻结在失败前的最后活跃阶段。 */
  progressKey: Exclude<AnalysisStageKey, "idle" | "failed" | "cancelled">;
}

const STAGE_LABELS: Record<AnalysisStageKey, string> = {
  idle: "仅对话",
  clarification: "等待指标澄清",
  planning: "制定分析计划",
  retrieving: "检索指标与知识",
  synthesizing: "验证证据",
  completed: "分析已完成",
  failed: "分析失败",
  cancelled: "已取消",
};

interface StageMark {
  key: Exclude<AnalysisStageKey, "idle">;
}

/**
 * retrieving 阶段目前由进度事件的"阶段 retrieving"消息前缀表达（T17/T18 的确定性消息），
 * 尚无结构化字段；后端事件契约补 stage 字段前，这里是唯一的解析点。
 */
const RETRIEVING_MESSAGE_PREFIX = "阶段 retrieving";

/** 事件流中的确定性阶段标记：T17/T18 的事件类型与"阶段 retrieving"消息前缀。 */
function stageMarkOf(event: AgentRun["auditEvents"][number]): StageMark | undefined {
  switch (event.eventType) {
    case "agent.run.clarification":
      return { key: "clarification" };
    case "agent.run.plan":
      return { key: "planning" };
    case "agent.run.finding":
      return { key: "synthesizing" };
    case "agent.run.completed":
    case "agent.run.failed":
    case "agent.run.cancelled":
      return { key: event.eventType === "agent.run.completed"
        ? "completed"
        : event.eventType === "agent.run.failed" ? "failed" : "cancelled" };
    case "agent.run.progress":
      return event.message.startsWith(RETRIEVING_MESSAGE_PREFIX) ? { key: "retrieving" } : undefined;
    default:
      return undefined;
  }
}

/** 从任务及其运行的有序事件推导当前阶段；终态以最后一个终态事件为准。 */
export function deriveStage(task: AnalysisTask | undefined, runs: AgentRun[]): AnalysisStage {
  if (!task) {
    return { key: "idle", label: STAGE_LABELS.idle, waiting: false, progressKey: "clarification" };
  }
  const events = runs
    .filter((run) => run.analysisTaskId === task.analysisTaskId)
    .flatMap((run) => run.auditEvents);
  const marks = events
    .map(stageMarkOf)
    .filter((mark): mark is StageMark => mark !== undefined);
  const isTerminal = (mark: StageMark): mark is { key: "completed" | "failed" | "cancelled" } =>
    mark.key === "completed" || mark.key === "failed" || mark.key === "cancelled";
  const terminalMarks = marks.filter(isTerminal);
  const activeMarks = marks.filter((mark): mark is { key: AnalysisStage["progressKey"] } => !isTerminal(mark));
  const progressKey = activeMarks.length > 0 ? activeMarks[activeMarks.length - 1].key : "clarification";
  const key: AnalysisStageKey = terminalMarks.length > 0 ? terminalMarks[terminalMarks.length - 1].key : progressKey;
  const waiting = key === "clarification" || task.status === "waiting_for_input";
  return { key, label: STAGE_LABELS[key], waiting, progressKey };
}

export interface StageRow {
  name: string;
  state: "done" | "active" | "pending";
}

const STAGE_ORDER: Record<Exclude<AnalysisStageKey, "idle" | "failed" | "cancelled">, number> = {
  clarification: 0,
  planning: 1,
  retrieving: 2,
  synthesizing: 3,
  completed: 4,
};

const STAGE_ROW_NAMES = ["理解问题", "检索口径", "执行查询", "验证证据", "生成报告"];

/** 任务状态徽章文案；与 AnalysisTask.status 的 OpenAPI 枚举一一对应。 */
export const TASK_BADGE_LABELS: Record<AnalysisTask["status"], string> = {
  active: "进行中",
  waiting_for_input: "等待输入",
  waiting_for_approval: "等待审批",
  completed: "已完成",
  failed: "已失败",
  cancelled: "已取消",
};

/** 五个业务阶段的推进视图：报告在 completed 时仍为待生成（T25 交付）。 */
export function stageProgress(stage: AnalysisStage): StageRow[] {
  const reached = STAGE_ORDER[stage.progressKey];
  return STAGE_ROW_NAMES.map((name, index) => ({
    name,
    state: index < reached ? "done"
      : index === reached ? "active"
      : "pending",
  }));
}

export interface EvidenceCell {
  text: string;
  tone: "positive" | "negative" | "neutral";
}

/** 证据单元格：数字千分位并带正负语义色，日期与文本原样。 */
export function formatEvidenceCell(value: unknown): EvidenceCell {
  if (value === null || value === undefined) return { text: "空", tone: "neutral" };
  if (typeof value === "number") {
    return {
      text: value.toLocaleString("en-US"),
      tone: value > 0 ? "positive" : value < 0 ? "negative" : "neutral",
    };
  }
  if (typeof value === "object") {
    return { text: JSON.stringify(value), tone: "neutral" };
  }
  return { text: String(value), tone: "neutral" };
}

export interface FindingBadge {
  text: string;
  tone: "verified" | "unverified";
}

export function findingBadge(finding: Pick<AnalysisFinding, "verified">): FindingBadge {
  return finding.verified
    ? { text: "已验证", tone: "verified" }
    : { text: "证据不足", tone: "unverified" };
}
