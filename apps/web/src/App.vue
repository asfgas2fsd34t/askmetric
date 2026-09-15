<script setup lang="ts">
import {
  Braces,
  Building2,
  CircleDashed,
  Database,
  FileSearch,
  FolderOpen,
  ListChecks,
  LogOut,
  Maximize2,
  MessagesSquare,
  Moon,
  Plus,
  RefreshCw,
  Send,
  Square,
  Sun,
  UserRound,
} from "lucide-vue-next";
import { computed, onMounted, onUnmounted, ref } from "vue";

import { accessToken, logout } from "./auth";
import {
  deriveStage,
  findingBadge,
  formatEvidenceCell,
  stageProgress,
  TASK_BADGE_LABELS,
} from "./analysis-context";
import {
  approveActionProposal,
  cancelAgentRun,
  createConversation,
  createRevisionProposal,
  discardActionProposal,
  createMessage,
  confirmUserMemory,
  createConversationSummary,
  deleteUserMemory,
  loadConversation,
  loadConversations,
  loadAwaitingApprovalProposals,
  loadKnowledgeSources,
  loadUserMemories,
  proposeUserMemory,
  uploadKnowledgeSource,
  rejectActionProposal,
  watchAgentRun,
  confirmActionProposal,
  type ActionProposal,
  type AgentRunEvent,
  type ConversationSnapshot,
  type ConversationListItem,
  type EvidenceSnapshot,
  type KnowledgeSource,
  type UserMemory,
} from "./conversation";
import { loadWorkspaceSession, type WorkspaceSession } from "./session";
import {
  proposalActionTypeLabel,
  proposalDecisionLine,
  proposalIsActionable,
  proposalIsApprovable,
  proposalStatusBadgeClass,
  proposalStatusLabel,
} from "./action-proposals";
import { applyTheme, initialTheme, toggleTheme, type ThemePreference } from "./theme";

const session = ref<WorkspaceSession>();
const conversations = ref<ConversationListItem[]>([]);
const activeConversation = ref<ConversationSnapshot>();
const loading = ref(true);
const conversationLoading = ref(false);
const sending = ref(false);
const cancellingRunId = ref("");
const error = ref("");
const conversationError = ref("");
const draft = ref("");
const theme = ref<ThemePreference>("light");
const inspectorOpen = ref(false);
const panelResizing = ref(false);
const evidenceDetail = ref<EvidenceSnapshot>();
const knowledgeSources = ref<KnowledgeSource[]>([]);
const knowledgeUploading = ref(false);
const knowledgeError = ref("");
const userMemories = ref<UserMemory[]>([]);
const memoryDraft = ref("");
const memoryBusy = ref(false);
const memoryError = ref("");
const runSubscriptions = new Map<string, () => void>();
const activeAgentRuns = computed(() => activeConversation.value?.agentRuns.filter((run) => !runIsTerminal(run)) ?? []);
const activeAgentRun = computed(() => activeAgentRuns.value.at(-1));
const activeAnalysisRun = computed(() => activeAgentRuns.value.findLast((run) => Boolean(run.analysisTaskId)));
const evidenceSnapshots = computed(() => activeConversation.value?.evidenceSnapshots ?? []);
const latestCancellation = computed(() => activeConversation.value?.agentRuns
  .flatMap((run) => run.auditEvents)
  .filter((event) => event.eventType === "agent.run.cancelled")
  .at(-1));
const latestFailure = computed(() => activeConversation.value?.agentRuns
  .flatMap((run) => run.auditEvents)
  .filter((event) => event.eventType === "agent.run.failed")
  .at(-1));
/** 对话焦点任务：最新创建的任务（搁置与终态任务也如实展示其状态）。 */
const currentTask = computed(() => activeConversation.value?.analysisTasks.at(-1));
const analysisStage = computed(() =>
  deriveStage(currentTask.value, activeConversation.value?.agentRuns ?? []));
const stageRows = computed(() => stageProgress(analysisStage.value));
const analysisFindings = computed(() => activeConversation.value?.analysisFindings ?? []);
const inspectorState = computed(() => ({
  conversationId: activeConversation.value?.conversationId ?? null,
  stage: analysisStage.value.key,
  waitingForUser: analysisStage.value.waiting,
  analysisTasks: (activeConversation.value?.analysisTasks ?? []).map((task) => ({
    id: task.analysisTaskId,
    status: task.status,
  })),
  findings: analysisFindings.value.map((finding) => ({
    id: finding.findingId,
    verified: finding.verified,
    evidence: finding.evidenceSnapshotIds.length,
  })),
  runs: (activeConversation.value?.agentRuns ?? []).map((run) => ({
    id: run.runId,
    lastEvent: run.auditEvents.at(-1)?.eventType ?? "none",
  })),
  recentEvents: (activeConversation.value?.agentRuns ?? [])
    .flatMap((run) => run.auditEvents)
    .slice(-8)
    .map((event) => ({ sequence: event.sequence, type: event.eventType })),
}));

function stopRunSubscriptions() {
  runSubscriptions.forEach((stop) => stop());
  runSubscriptions.clear();
}

function runIsTerminal(run: NonNullable<typeof activeConversation.value>["agentRuns"][number]) {
  const type = run.auditEvents.at(-1)?.eventType;
  return type === "agent.run.completed" || type === "agent.run.failed" || type === "agent.run.cancelled";
}

async function activateConversation(snapshot: ConversationSnapshot | undefined, expectedConversationId?: string) {
  if (expectedConversationId && activeConversation.value?.conversationId !== expectedConversationId) return;
  stopRunSubscriptions();
  activeConversation.value = snapshot;
  if (!snapshot || !session.value) return;

  const token = await accessToken();
  if (activeConversation.value?.conversationId !== snapshot.conversationId) return;
  for (const run of snapshot.agentRuns.filter((candidate) => !runIsTerminal(candidate))) {
    const afterSequence = run.auditEvents.at(-1)?.sequence ?? 0;
    const stop = watchAgentRun(
      token,
      session.value.currentMembership.workspaceId,
      snapshot.conversationId,
      run.runId,
      afterSequence,
      applyAgentRunEvent,
    );
    runSubscriptions.set(run.runId, stop);
  }
}

function applyAgentRunEvent(event: AgentRunEvent) {
  const snapshot = activeConversation.value;
  if (!snapshot || snapshot.conversationId !== event.conversationId) return;

  let received = false;
  const agentRuns = snapshot.agentRuns.map((run) => {
    if (run.runId !== event.runId) return run;
    if (run.auditEvents.some((existing) => existing.eventId === event.eventId || existing.sequence >= event.sequence)) {
      return run;
    }
    received = true;
    return { ...run, auditEvents: [...run.auditEvents, event] };
  });
  if (!received) return;
  activeConversation.value = { ...snapshot, agentRuns };

  if (event.eventType === "agent.run.completed"
    || event.eventType === "agent.run.failed"
    || event.eventType === "agent.run.cancelled") {
    runSubscriptions.get(event.runId)?.();
    runSubscriptions.delete(event.runId);
    if (session.value) {
      void refreshCompletedRun(session.value.currentMembership.workspaceId, snapshot.conversationId);
    }
  }
}

async function cancelRun(runId: string) {
  if (!session.value || !activeConversation.value || cancellingRunId.value) return;
  cancellingRunId.value = runId;
  conversationError.value = "";
  try {
    const event = await cancelAgentRun(
      await accessToken(),
      session.value.currentMembership.workspaceId,
      activeConversation.value.conversationId,
      runId,
    );
    applyAgentRunEvent(event);
  } catch {
    conversationError.value = "无法停止分析";
  } finally {
    cancellingRunId.value = "";
  }
}

async function refreshCompletedRun(workspaceId: string, conversationId: string) {
  let delay = 500;
  while (
    activeConversation.value?.conversationId === conversationId &&
    session.value?.currentMembership.workspaceId === workspaceId
  ) {
    try {
      const snapshot = await loadConversation(await accessToken(), workspaceId, conversationId);
      await activateConversation(snapshot, conversationId);
      return;
    } catch {
      conversationError.value = "无法加载对话";
      await new Promise((resolve) => setTimeout(resolve, delay));
      delay = Math.min(delay * 2, 10_000);
    }
  }
}

async function refresh(requestedWorkspaceId?: string) {
  loading.value = true;
  error.value = "";
  try {
    session.value = await loadWorkspaceSession(await accessToken(), requestedWorkspaceId);
    await refreshConversations(session.value.currentMembership.workspaceId);
    void refreshKnowledge(session.value.currentMembership.workspaceId);
    void refreshMemories(session.value.currentMembership.workspaceId);
    void refreshApprovalQueue(session.value.currentMembership.workspaceId);
  } catch {
    error.value = "无法加载工作区";
  } finally {
    loading.value = false;
  }
}

async function refreshKnowledge(workspaceId: string) {
  if (!session.value) return;
  try {
    knowledgeSources.value = await loadKnowledgeSources(await accessToken(), workspaceId);
    knowledgeError.value = "";
  } catch {
    knowledgeError.value = "无法加载知识来源";
  }
}

const proposalBusy = ref(false);
const proposalError = ref("");
const approvalQueue = ref<ActionProposal[]>([]);
const revisionDraft = ref({ metricKey: "mrr", versionLabel: "", calculationRule: "", timeBoundary: "", exclusions: "" });

async function refreshApprovalQueue(workspaceId: string) {
  if (!session.value) return;
  try {
    approvalQueue.value = await loadAwaitingApprovalProposals(await accessToken(), workspaceId);
  } catch {
    approvalQueue.value = [];
  }
}

/** 有权限成员批准提案；不可逆终态，弹框复述确切参数。 */
async function approveProposal(proposal: ActionProposal) {
  if (!session.value || proposalBusy.value) return;
  const confirmed = window.confirm(
    `批准该提案？此操作不可逆。
类型：${proposalActionTypeLabel(proposal.actionType)}`
    + `
计算规则：${proposal.calculationRule}
时间边界：${proposal.timeBoundary}`
    + `
排除项：${proposal.exclusions}
幂等键：${proposal.idempotencyKey}`);
  if (!confirmed) return;
  proposalBusy.value = true;
  proposalError.value = "";
  try {
    await approveActionProposal(
      await accessToken(), session.value.currentMembership.workspaceId, proposal.actionProposalId);
    await refresh();
    await refreshApprovalQueue(session.value.currentMembership.workspaceId);
  } catch {
    proposalError.value = "无法批准提案（权限、分离审批或策略版本守卫未通过）";
  } finally {
    proposalBusy.value = false;
  }
}

/** 有权限成员拒绝提案；不可逆终态，不产生副作用。 */
async function rejectProposal(proposal: ActionProposal) {
  if (!session.value || proposalBusy.value) return;
  if (!window.confirm(`拒绝该提案？此操作不可逆，提案不会执行。`)) return;
  proposalBusy.value = true;
  proposalError.value = "";
  try {
    await rejectActionProposal(
      await accessToken(), session.value.currentMembership.workspaceId, proposal.actionProposalId);
    await refresh();
    await refreshApprovalQueue(session.value.currentMembership.workspaceId);
  } catch {
    proposalError.value = "无法拒绝提案";
  } finally {
    proposalBusy.value = false;
  }
}

/** 有审批权限成员直建标准口径修订提案（ADR-0009 自上而下路径）。 */
async function submitRevision() {
  if (!session.value || proposalBusy.value) return;
  const draft = revisionDraft.value;
  if (!draft.versionLabel.trim() || !draft.calculationRule.trim()
    || !draft.timeBoundary.trim() || !draft.exclusions.trim()) {
    proposalError.value = "修订提案需要完整填写版本、规则、边界与排除项";
    return;
  }
  if (!window.confirm(
    `提交标准口径修订提案？
${draft.metricKey} ${draft.versionLabel}`
    + `
计算规则：${draft.calculationRule}
提交后进入审批队列，参数不可修改。`)) return;
  proposalBusy.value = true;
  proposalError.value = "";
  try {
    await createRevisionProposal(
      await accessToken(), session.value.currentMembership.workspaceId, {
        metricKey: draft.metricKey.trim(),
        versionLabel: draft.versionLabel.trim(),
        calculationRule: draft.calculationRule.trim(),
        timeBoundary: draft.timeBoundary.trim(),
        exclusions: draft.exclusions.trim(),
      });
    revisionDraft.value = { metricKey: draft.metricKey, versionLabel: "", calculationRule: "", timeBoundary: "", exclusions: "" };
    await refreshApprovalQueue(session.value.currentMembership.workspaceId);
  } catch {
    proposalError.value = "无法创建修订提案";
  } finally {
    proposalBusy.value = false;
  }
}

/** 发起者显式确认提案创建；确认弹框防止误操作，提案自此等待审批且参数不可变。 */
async function confirmProposal(proposal: ActionProposal) {
  if (!session.value || proposalBusy.value) return;
  const confirmed = window.confirm(
    `确认创建该操作提案？\n类型：${proposalActionTypeLabel(proposal.actionType)}`
    + `\n计算规则：${proposal.calculationRule}\n幂等键：${proposal.idempotencyKey}`
    + "\n确认后提案进入等待审批，参数不可修改。");
  if (!confirmed) return;
  proposalBusy.value = true;
  proposalError.value = "";
  try {
    await confirmActionProposal(
      await accessToken(), session.value.currentMembership.workspaceId, proposal.actionProposalId);
    await refresh();
  } catch {
    proposalError.value = "无法确认操作提案";
  } finally {
    proposalBusy.value = false;
  }
}

/** 发起者放弃提案草案；放弃的提案不会产生任何副作用。 */
async function discardProposal(proposal: ActionProposal) {
  if (!session.value || proposalBusy.value) return;
  proposalBusy.value = true;
  proposalError.value = "";
  try {
    await discardActionProposal(
      await accessToken(), session.value.currentMembership.workspaceId, proposal.actionProposalId);
    await refresh();
  } catch {
    proposalError.value = "无法放弃操作提案";
  } finally {
    proposalBusy.value = false;
  }
}

async function refreshMemories(workspaceId: string) {
  if (!session.value) return;
  try {
    userMemories.value = await loadUserMemories(await accessToken(), workspaceId);
    memoryError.value = "";
  } catch {
    memoryError.value = "无法加载记忆";
  }
}

async function submitMemory() {
  const content = memoryDraft.value.trim();
  if (!session.value || !content || memoryBusy.value) return;
  memoryBusy.value = true;
  memoryError.value = "";
  try {
    await proposeUserMemory(await accessToken(), session.value.currentMembership.workspaceId, content);
    memoryDraft.value = "";
    await refreshMemories(session.value.currentMembership.workspaceId);
  } catch {
    memoryError.value = "无法登记记忆";
  } finally {
    memoryBusy.value = false;
  }
}

async function confirmMemory(userMemoryId: string) {
  if (!session.value || memoryBusy.value) return;
  memoryBusy.value = true;
  try {
    await confirmUserMemory(
      await accessToken(), session.value.currentMembership.workspaceId, userMemoryId);
    await refreshMemories(session.value.currentMembership.workspaceId);
  } catch {
    memoryError.value = "无法确认记忆";
  } finally {
    memoryBusy.value = false;
  }
}

async function removeMemory(userMemoryId: string) {
  if (!session.value || memoryBusy.value) return;
  memoryBusy.value = true;
  try {
    await deleteUserMemory(
      await accessToken(), session.value.currentMembership.workspaceId, userMemoryId);
    await refreshMemories(session.value.currentMembership.workspaceId);
  } catch {
    memoryError.value = "无法删除记忆";
  } finally {
    memoryBusy.value = false;
  }
}

async function summarizeConversation() {
  if (!session.value || !activeConversation.value || memoryBusy.value) return;
  const messages = activeConversation.value.messages;
  if (messages.length === 0) return;
  memoryBusy.value = true;
  memoryError.value = "";
  try {
    await createConversationSummary(
      await accessToken(),
      session.value.currentMembership.workspaceId,
      activeConversation.value.conversationId,
      messages[0].sequence,
      messages[messages.length - 1].sequence,
    );
    await refreshConversations(
      session.value.currentMembership.workspaceId, activeConversation.value.conversationId);
  } catch {
    memoryError.value = "无法创建摘要";
  } finally {
    memoryBusy.value = false;
  }
}

async function onKnowledgeFileChosen(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || !session.value || knowledgeUploading.value) return;
  knowledgeUploading.value = true;
  knowledgeError.value = "";
  try {
    await uploadKnowledgeSource(await accessToken(), session.value.currentMembership.workspaceId, file);
    await refreshKnowledge(session.value.currentMembership.workspaceId);
  } catch {
    knowledgeError.value = "无法上传知识来源";
  } finally {
    knowledgeUploading.value = false;
  }
}

/** 重置视图：丢弃本地状态并重新加载工作区数据，不修改服务端事实。 */
function resetWorkspaceView() {
  inspectorOpen.value = false;
  draft.value = "";
  void refresh(session.value?.currentMembership.workspaceId);
}

function switchTheme() {
  theme.value = toggleTheme(theme.value);
}

const CONTEXT_WIDTH_KEY = "askmetric-context-width";
const contextWidth = ref<number>();

/** 上下文面板宽度限制在可用范围内，保证对话区仍有可用宽度。 */
function clampContextWidth(px: number) {
  const maximum = Math.max(280, Math.min(760, window.innerWidth - 560));
  return Math.round(Math.min(Math.max(px, 280), maximum));
}

function applyContextWidth(px: number, persist = false) {
  const width = clampContextWidth(px);
  contextWidth.value = width;
  document.documentElement.style.setProperty("--context-width", width + "px");
  if (persist) {
    try {
      localStorage.setItem(CONTEXT_WIDTH_KEY, String(width));
    } catch {
      // 持久化失败不影响当前会话。
    }
  }
}

function startPanelResize(event: PointerEvent) {
  event.preventDefault();
  panelResizing.value = true;
  window.addEventListener("pointermove", resizePanel);
  window.addEventListener("pointerup", stopPanelResize);
}

function resizePanel(event: PointerEvent) {
  applyContextWidth(window.innerWidth - event.clientX);
}

function stopPanelResize() {
  panelResizing.value = false;
  window.removeEventListener("pointermove", resizePanel);
  window.removeEventListener("pointerup", stopPanelResize);
  if (contextWidth.value !== undefined) {
    applyContextWidth(contextWidth.value, true);
  }
}

function adjustPanelWidthByKey(event: KeyboardEvent) {
  const step = event.key === "ArrowLeft" ? 24 : event.key === "ArrowRight" ? -24 : 0;
  if (step === 0) {
    return;
  }
  event.preventDefault();
  applyContextWidth((contextWidth.value ?? 320) + step, true);
}

function selectWorkspace(event: Event) {
  stopRunSubscriptions();
  activeConversation.value = undefined;
  conversations.value = [];
  void refresh((event.target as HTMLSelectElement).value);
}

async function refreshConversations(workspaceId: string, preferredConversationId?: string): Promise<boolean> {
  conversationLoading.value = true;
  conversationError.value = "";
  try {
    const token = await accessToken();
    conversations.value = await loadConversations(token, workspaceId);
    const conversationId = preferredConversationId ?? conversations.value[0]?.conversationId;
    await activateConversation(conversationId ? await loadConversation(token, workspaceId, conversationId) : undefined);
    return true;
  } catch {
    conversationError.value = "无法加载对话";
    return false;
  } finally {
    conversationLoading.value = false;
  }
}

async function openConversation(conversationId: string) {
  if (!session.value || conversationId === activeConversation.value?.conversationId) return;
  conversationLoading.value = true;
  conversationError.value = "";
  try {
    await activateConversation(await loadConversation(
      await accessToken(),
      session.value.currentMembership.workspaceId,
      conversationId,
    ));
  } catch {
    conversationError.value = "无法打开对话";
  } finally {
    conversationLoading.value = false;
  }
}

async function startConversation() {
  if (!session.value || conversationLoading.value) return;
  conversationLoading.value = true;
  conversationError.value = "";
  try {
    const token = await accessToken();
    const created = await createConversation(
      token,
      session.value.currentMembership.workspaceId,
      "新对话",
    );
    await refreshConversations(session.value.currentMembership.workspaceId, created.conversationId);
  } catch {
    conversationError.value = "无法新建对话";
    conversationLoading.value = false;
  }
}

/** Enter 发送、Shift+Enter 换行；输入法组合确认的 Enter 不视为发送。 */
function composerKeydown(event: KeyboardEvent) {
  if (event.key !== "Enter" || event.isComposing || event.keyCode === 229) {
    return;
  }
  if (event.shiftKey || event.altKey) {
    return;
  }
  event.preventDefault();
  void submitMessage();
}

async function submitMessage() {
  const content = draft.value.trim();
  if (!session.value || !activeConversation.value || !content || sending.value) return;
  sending.value = true;
  conversationError.value = "";
  try {
    const token = await accessToken();
    const workspaceId = session.value.currentMembership.workspaceId;
    const conversationId = activeConversation.value.conversationId;
    const accepted = await createMessage(
      token,
      workspaceId,
      conversationId,
      content,
      crypto.randomUUID(),
    );
    await activateConversation({
      ...activeConversation.value,
      messages: [
        ...activeConversation.value.messages,
        accepted.userMessage,
        ...(accepted.assistantMessage ? [accepted.assistantMessage] : []),
      ],
      agentRuns: [...activeConversation.value.agentRuns, accepted.agentRun],
      analysisTasks: [
        ...activeConversation.value.analysisTasks.map((task) =>
          accepted.analysisTask && task.status === "active"
            ? { ...task, status: "waiting_for_input" as const }
            : task,
        ),
        ...(accepted.analysisTask ? [accepted.analysisTask] : []),
      ],
    });
    draft.value = "";
    await refreshConversations(workspaceId, conversationId);
  } catch {
    conversationError.value = "消息发送失败";
  } finally {
    sending.value = false;
  }
}

onMounted(() => {
  theme.value = initialTheme();
  applyTheme(theme.value);
  let storedWidth = 0;
  try {
    storedWidth = Number(localStorage.getItem(CONTEXT_WIDTH_KEY)) || 0;
  } catch {
    // 读取失败时使用默认宽度。
  }
  if (storedWidth > 0) {
    applyContextWidth(storedWidth);
  }
  window.addEventListener("keydown", closeInspectorOnEscape);
  void refresh();
});
onUnmounted(() => {
  window.removeEventListener("keydown", closeInspectorOnEscape);
  stopPanelResize();
  stopRunSubscriptions();
});

function closeInspectorOnEscape(event: KeyboardEvent) {
  if (event.key === "Escape") {
    inspectorOpen.value = false;
    evidenceDetail.value = undefined;
  }
}
</script>

<template>
  <div v-if="loading && !session" class="status-screen" aria-live="polite">
    <span class="status-mark" />
    <span>正在加载工作区</span>
  </div>

  <div v-else-if="error && !session" class="status-screen" role="alert">
    <strong>{{ error }}</strong>
    <button class="command-button" type="button" @click="refresh()">
      <RefreshCw :size="16" aria-hidden="true" />
      重试
    </button>
  </div>

  <div v-else-if="session" class="workspace-shell">
    <header class="app-header">
      <div class="brand-lockup">
        <span class="brand-mark">A</span>
        <strong>AskMetric</strong>
      </div>

      <label class="workspace-select">
        <Building2 :size="16" aria-hidden="true" />
        <span class="sr-only">当前工作区</span>
        <select :value="session.currentMembership.workspaceId" :disabled="loading" @change="selectWorkspace">
          <option
            v-for="membership in session.memberships"
            :key="membership.membershipId"
            :value="membership.workspaceId"
          >
            {{ membership.workspaceName }}
          </option>
        </select>
      </label>

      <div class="header-actions">
        <button
          class="icon-button"
          type="button"
          title="重置视图"
          aria-label="重置视图"
          :disabled="loading"
          @click="resetWorkspaceView"
        >
          <RefreshCw :size="17" aria-hidden="true" />
        </button>
        <button
          class="icon-button"
          type="button"
          :title="theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
          :aria-label="theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
          @click="switchTheme"
        >
          <Sun v-if="theme === 'dark'" :size="17" aria-hidden="true" />
          <Moon v-else :size="17" aria-hidden="true" />
        </button>
        <button
          class="icon-button"
          type="button"
          title="状态检查器"
          aria-label="状态检查器"
          :aria-expanded="inspectorOpen"
          @click="inspectorOpen = !inspectorOpen"
        >
          <Braces :size="17" aria-hidden="true" />
        </button>
        <div class="identity-control">
          <span class="identity-avatar" aria-hidden="true">{{ session.user.displayName.slice(0, 1) }}</span>
          <span class="identity-copy">
            <strong>{{ session.user.displayName }}</strong>
            <small>{{ session.user.email }}</small>
          </span>
          <button class="icon-button" type="button" title="退出登录" aria-label="退出登录" @click="logout">
            <LogOut :size="18" aria-hidden="true" />
          </button>
        </div>
      </div>
    </header>

    <main class="workspace-grid" :class="{ 'panel-resizing': panelResizing }">
      <aside class="conversation-rail" aria-labelledby="conversations-heading">
        <div class="panel-heading">
          <MessagesSquare :size="17" aria-hidden="true" />
          <h2 id="conversations-heading">对话</h2>
          <button
            class="icon-button rail-action"
            type="button"
            title="新建对话"
            aria-label="新建对话"
            :disabled="conversationLoading"
            @click="startConversation"
          >
            <Plus :size="17" aria-hidden="true" />
          </button>
        </div>
        <div v-if="conversationError" class="conversation-error" role="alert">{{ conversationError }}</div>
        <div v-if="!conversationLoading && conversations.length === 0" class="empty-list">暂无对话</div>
        <nav v-else class="conversation-list" aria-label="对话列表">
          <button
            v-for="conversation in conversations"
            :key="conversation.conversationId"
            class="conversation-item"
            :class="{ active: conversation.conversationId === activeConversation?.conversationId }"
            type="button"
            @click="openConversation(conversation.conversationId)"
          >
            <strong>{{ conversation.title }}</strong>
            <small>{{ conversation.messageCount }} 条消息</small>
          </button>
        </nav>
      </aside>

      <section class="conversation-canvas" aria-labelledby="workspace-heading">
        <div v-if="activeConversation" class="conversation-view">
          <header class="conversation-header">
            <h1 id="workspace-heading">{{ activeConversation.title }}</h1>
          </header>
          <div class="message-timeline" aria-live="polite">
            <article
              v-for="message in activeConversation.messages"
              :key="message.messageId"
              class="message"
              :class="`message-${message.author}`"
            >
              <span>
                {{
                  message.author === "user"
                    ? message.authorSubject === session.user.id
                      ? session.user.displayName
                      : (message.authorSubject ?? "用户")
                    : message.author === "assistant"
                      ? "AskMetric"
                      : "系统"
                }}
              </span>
              <p>{{ message.content }}</p>
              <time :datetime="message.createdAt">{{ new Date(message.createdAt).toLocaleTimeString() }}</time>
            </article>
            <div v-if="activeConversation.messages.length === 0" class="empty-list">暂无消息</div>
          </div>
          <div v-if="activeAgentRun" class="agent-run-status" role="status" aria-live="polite">
            <CircleDashed :size="16" aria-hidden="true" />
            <div>
              <strong>{{ activeAgentRun.auditEvents.at(-1)?.message ?? "正在处理" }}</strong>
              <small v-if="activeAgentRuns.length > 1">另有 {{ activeAgentRuns.length - 1 }} 个任务正在处理</small>
            </div>
            <button
              v-if="activeAnalysisRun"
              class="stop-run-button"
              type="button"
              title="停止分析"
              aria-label="停止分析"
              :disabled="cancellingRunId === activeAnalysisRun.runId"
              @click="cancelRun(activeAnalysisRun.runId)"
            >
              <Square :size="13" aria-hidden="true" />
            </button>
          </div>
          <div v-if="latestCancellation" class="agent-run-cancelled" role="status">
            {{ latestCancellation.message }}
          </div>
          <div v-if="latestFailure" class="agent-run-failed" role="alert">
            {{ latestFailure.message }}
          </div>
          <form class="message-composer" @submit.prevent="submitMessage">
            <label class="sr-only" for="message-draft">消息</label>
            <textarea
              id="message-draft"
              v-model="draft"
              maxlength="4000"
              rows="2"
              placeholder="输入消息（Enter 发送，Shift+Enter 换行）"
              :disabled="sending"
              @keydown="composerKeydown"
            />
            <button
              class="send-button"
              type="submit"
              title="发送消息"
              aria-label="发送消息"
              :disabled="sending || !draft.trim()"
            >
              <Send :size="18" aria-hidden="true" />
            </button>
          </form>
        </div>
        <div v-else class="workspace-welcome">
          <span class="welcome-icon"><UserRound :size="22" aria-hidden="true" /></span>
          <p>{{ session.currentMembership.workspaceName }}</p>
          <h1 id="workspace-heading">你好，{{ session.user.displayName }}</h1>
        </div>
      </section>

      <aside class="context-panel" aria-labelledby="context-heading">
        <div
          class="panel-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整上下文面板宽度"
          tabindex="0"
          @pointerdown="startPanelResize"
          @keydown="adjustPanelWidthByKey"
        />
        <div class="panel-heading">
          <ListChecks :size="17" aria-hidden="true" />
          <h2 id="context-heading">分析上下文</h2>
          <span v-if="activeConversation" class="live-badge">LIVE</span>
        </div>

        <section class="context-section" aria-labelledby="task-heading">
          <h3 id="task-heading">当前任务</h3>
          <div v-if="!currentTask" class="context-empty">
            当前对话只有普通对话，没有 Analysis Task。发送分析问题后才会创建任务。
          </div>
          <template v-else>
            <div class="task-summary">
              <strong class="task-goal">{{ currentTask.goal }}</strong>
              <div class="task-meta">
                <span class="task-badge" :class="`task-${currentTask.status}`">
                  {{ TASK_BADGE_LABELS[currentTask.status] }}
                </span>
                <small class="task-id">{{ currentTask.analysisTaskId }}</small>
              </div>
            </div>
            <ol class="stage-list">
              <li v-for="row in stageRows" :key="row.name" class="stage-row" :class="`stage-${row.state}`">
                <span class="stage-mark" aria-hidden="true">{{ row.state === "done" ? "✓" : row.state === "active" ? "●" : "○" }}</span>
                <span>{{ row.name }}</span>
              </li>
            </ol>
            <p v-if="analysisStage.waiting" class="stage-waiting">等待你的输入后继续推进</p>
            <p v-else-if="analysisStage.key === 'failed'" class="stage-failed-note">分析在「{{ stageRows.find((row) => row.state === 'active')?.name ?? "理解问题" }}」阶段失败</p>
            <p v-else-if="analysisStage.key === 'cancelled'" class="stage-failed-note">分析在「{{ stageRows.find((row) => row.state === 'active')?.name ?? "理解问题" }}」阶段取消</p>
          </template>
        </section>

        <section v-if="analysisFindings.length > 0" class="context-section" aria-labelledby="findings-heading">
          <h3 id="findings-heading">主要发现</h3>
          <article v-for="finding in analysisFindings" :key="finding.findingId" class="finding-card">
            <header class="finding-header">
              <span class="finding-badge" :class="`finding-${findingBadge(finding).tone}`">
                {{ findingBadge(finding).text }}
              </span>
              <small class="finding-metric">{{ finding.metricDefinitionVersionId }}</small>
            </header>
            <p class="finding-conclusion">{{ finding.conclusion }}</p>
            <div class="finding-evidence">
              <Database :size="13" aria-hidden="true" />
              <span>证据 × {{ finding.evidenceSnapshotIds.length }}</span>
            </div>
            <details v-if="finding.knowledgeCitations?.length" class="finding-details">
              <summary>知识引用（{{ finding.knowledgeCitations.length }}）</summary>
              <ul>
                <li v-for="(citation, index) in finding.knowledgeCitations" :key="index">
                  <small class="citation-source">{{ citation.knowledgeSourceId }} · 第 {{ citation.passageNumber }} 段</small>
                  <blockquote class="citation-quote">{{ citation.quote }}</blockquote>
                </li>
              </ul>
            </details>
            <details v-if="finding.assumptions.length > 0" class="finding-details">
              <summary>假设（{{ finding.assumptions.length }}）</summary>
              <ul>
                <li v-for="(assumption, index) in finding.assumptions" :key="index">{{ assumption }}</li>
              </ul>
            </details>
            <details v-if="finding.uncertainties.length > 0" class="finding-details">
              <summary>不确定性（{{ finding.uncertainties.length }}）</summary>
              <ul>
                <li v-for="(uncertainty, index) in finding.uncertainties" :key="index">{{ uncertainty }}</li>
              </ul>
            </details>
          </article>
        </section>

        <section
          v-if="(activeConversation?.actionProposals?.length ?? 0) > 0"
          class="context-section"
          aria-labelledby="proposals-heading"
        >
          <h3 id="proposals-heading">操作提案</h3>
          <div v-if="proposalError" class="knowledge-error" role="alert">{{ proposalError }}</div>
          <article
            v-for="proposal in activeConversation?.actionProposals ?? []"
            :key="proposal.actionProposalId"
            class="finding-card proposal-card"
          >
            <header class="finding-header">
              <span class="finding-badge">{{ proposalActionTypeLabel(proposal.actionType) }}</span>
              <span class="task-badge" :class="proposalStatusBadgeClass(proposal.status)">
                {{ proposalStatusLabel(proposal.status) }}
              </span>
            </header>
            <p class="finding-metric">
              {{ proposal.metricKey.toUpperCase() }} · {{ proposal.versionLabel }} · 策略版本 v{{ proposal.policyVersion }}
            </p>
            <dl class="proposal-params">
              <div><dt>计算规则</dt><dd>{{ proposal.calculationRule }}</dd></div>
              <div><dt>时间边界</dt><dd>{{ proposal.timeBoundary }}</dd></div>
              <div><dt>排除项</dt><dd>{{ proposal.exclusions }}</dd></div>
              <div><dt>幂等键</dt><dd class="proposal-idempotency-key">{{ proposal.idempotencyKey }}</dd></div>
            </dl>
            <footer v-if="proposalIsActionable(proposal, session?.user?.id)" class="proposal-actions">
              <button
                class="proposal-confirm"
                type="button"
                :disabled="proposalBusy"
                @click="confirmProposal(proposal)"
              >
                确认创建提案
              </button>
              <button
                class="icon-button proposal-discard"
                type="button"
                title="放弃提案"
                aria-label="放弃提案"
                :disabled="proposalBusy"
                @click="discardProposal(proposal)"
              >
                ✕
              </button>
            </footer>
            <footer v-else-if="proposalIsApprovable(proposal)" class="proposal-actions">
              <button
                class="proposal-approve"
                type="button"
                :disabled="proposalBusy"
                @click="approveProposal(proposal)"
              >
                批准
              </button>
              <button
                class="proposal-reject"
                type="button"
                :disabled="proposalBusy"
                @click="rejectProposal(proposal)"
              >
                拒绝
              </button>
            </footer>
            <p v-else-if="proposalDecisionLine(proposal)" class="proposal-decision">
              {{ proposalDecisionLine(proposal) }}
            </p>
            <p v-else class="proposal-note">Agent 只能提出提案；批准与执行需要人工动作。</p>
          </article>
        </section>

        <section class="context-section" aria-labelledby="approval-heading">
          <h3 id="approval-heading">审批队列</h3>
          <details class="finding-details revision-form">
            <summary>提议修订标准口径（治理成员）</summary>
            <form class="memory-form revision-fields" @submit.prevent="submitRevision">
              <input v-model="revisionDraft.metricKey" placeholder="指标键（如 mrr）" aria-label="指标键" :disabled="proposalBusy" />
              <input v-model="revisionDraft.versionLabel" placeholder="版本（如 v4）" aria-label="版本" :disabled="proposalBusy" />
              <input v-model="revisionDraft.calculationRule" placeholder="计算规则" aria-label="计算规则" :disabled="proposalBusy" />
              <input v-model="revisionDraft.timeBoundary" placeholder="时间边界" aria-label="时间边界" :disabled="proposalBusy" />
              <input v-model="revisionDraft.exclusions" placeholder="排除项" aria-label="排除项" :disabled="proposalBusy" />
              <button class="memory-add" type="submit" :disabled="proposalBusy">提交提案</button>
            </form>
          </details>
          <div v-if="approvalQueue.length === 0" class="context-empty">暂无等待审批的提案。</div>
          <ul v-else class="memory-list">
            <li v-for="proposal in approvalQueue" :key="proposal.actionProposalId" class="memory-item">
              <div class="memory-item-main">
                <p>{{ proposalActionTypeLabel(proposal.actionType) }} · {{ proposal.metricKey }} {{ proposal.versionLabel }}</p>
                <small>发起者 {{ proposal.proposedBy }} · 策略版本 v{{ proposal.policyVersion }}</small>
              </div>
              <button
                class="proposal-approve"
                type="button"
                :disabled="proposalBusy"
                @click="approveProposal(proposal)"
              >
                批准
              </button>
              <button
                class="proposal-reject"
                type="button"
                :disabled="proposalBusy"
                @click="rejectProposal(proposal)"
              >
                拒绝
              </button>
            </li>
          </ul>
        </section>

        <section class="context-section" aria-labelledby="memory-heading">
          <h3 id="memory-heading">用户记忆</h3>
          <form class="memory-form" @submit.prevent="submitMemory">
            <input
              v-model="memoryDraft"
              maxlength="500"
              placeholder="输入偏好，确认后跨对话生效"
              :disabled="memoryBusy"
              aria-label="记忆内容"
            />
            <button
              class="memory-add"
              type="submit"
              title="登记待确认记忆"
              :disabled="memoryBusy || !memoryDraft.trim()"
            >
              登记
            </button>
          </form>
          <div v-if="memoryError" class="knowledge-error" role="alert">{{ memoryError }}</div>
          <div v-if="userMemories.length === 0" class="context-empty">
            暂无记忆。登记后需你明确确认，未确认的记忆不会进入任何分析。
          </div>
          <ul v-else class="memory-list">
            <li v-for="memory in userMemories" :key="memory.userMemoryId" class="memory-item">
              <div class="memory-item-main">
                <p>{{ memory.content }}</p>
                <small>{{ memory.status === "CONFIRMED" ? "已确认 · 跨对话可用" : "待确认 · 分析不可见" }}</small>
              </div>
              <span class="task-badge" :class="memory.status === 'CONFIRMED' ? 'knowledge-ready' : 'knowledge-uploaded'">
                {{ memory.status === "CONFIRMED" ? "已确认" : "待确认" }}
              </span>
              <button
                v-if="memory.status === 'PROPOSED'"
                class="icon-button memory-action"
                type="button"
                title="确认记忆"
                aria-label="确认记忆"
                :disabled="memoryBusy"
                @click="confirmMemory(memory.userMemoryId)"
              >
                ✓
              </button>
              <button
                class="icon-button memory-action"
                type="button"
                title="删除记忆"
                aria-label="删除记忆"
                :disabled="memoryBusy"
                @click="removeMemory(memory.userMemoryId)"
              >
                ✕
              </button>
            </li>
          </ul>
        </section>

        <section class="context-section" aria-labelledby="summary-heading">
          <h3 id="summary-heading">对话摘要</h3>
          <button
            class="knowledge-upload"
            type="button"
            :disabled="memoryBusy || !activeConversation?.messages?.length"
            @click="summarizeConversation"
          >
            {{ memoryBusy ? "处理中…" : "生成当前对话摘要" }}
          </button>
          <div v-if="activeConversation?.conversationSummaries?.length" class="summary-list">
            <details v-for="summary in activeConversation.conversationSummaries" :key="summary.conversationSummaryId" class="finding-details">
              <summary>v{{ summary.version }} · 覆盖消息 {{ summary.fromSequence }}–{{ summary.toSequence }}</summary>
              <pre class="summary-text">{{ summary.summaryText }}</pre>
            </details>
          </div>
          <div v-else class="context-empty">暂无摘要。摘要记录范围与版本，不替代原始消息。</div>
        </section>

        <section class="context-section" aria-labelledby="knowledge-heading">
          <h3 id="knowledge-heading">知识来源</h3>
          <div class="knowledge-actions">
            <label class="knowledge-upload" :class="{ busy: knowledgeUploading }">
              <FolderOpen :size="14" aria-hidden="true" />
              <span>{{ knowledgeUploading ? "摄取中…" : "上传知识" }}</span>
              <input
                type="file"
                accept=".md,.txt,.pdf,text/plain,text/markdown,application/pdf"
                :disabled="knowledgeUploading"
                @change="onKnowledgeFileChosen"
              />
            </label>
          </div>
          <div v-if="knowledgeError" class="knowledge-error" role="alert">{{ knowledgeError }}</div>
          <div v-if="knowledgeSources.length === 0" class="context-empty">
            暂无知识来源。上传 Markdown、纯文本或文本型 PDF 后，分析可以引用其段落。
          </div>
          <ul v-else class="knowledge-list">
            <li v-for="source in knowledgeSources" :key="source.knowledgeSourceId" class="knowledge-item">
              <div class="knowledge-item-main">
                <strong>{{ source.title }}</strong>
                <small>{{ source.passageCount }} 段 · {{ source.contentType }}</small>
              </div>
              <span class="task-badge" :class="`knowledge-${source.status.toLowerCase()}`">
                {{ source.status === "READY" ? "就绪" : source.status === "FAILED" ? "失败" : "已上传" }}
              </span>
            </li>
          </ul>
        </section>

        <section class="context-section" aria-labelledby="evidence-heading">
          <h3 id="evidence-heading">关键证据</h3>
          <div v-if="evidenceSnapshots.length === 0" class="context-empty">暂无证据</div>
          <div v-else class="evidence-list">
            <article v-for="snapshot in evidenceSnapshots" :key="snapshot.evidenceSnapshotId" class="evidence-card">
              <header class="evidence-header">
                <FileSearch :size="13" aria-hidden="true" />
                <strong>{{ snapshot.sourceTable }}</strong>
                <small>{{ snapshot.sourceRange }}</small>
                <button
                  class="icon-button evidence-expand"
                  type="button"
                  title="查看完整证据"
                  aria-label="查看完整证据"
                  @click="evidenceDetail = snapshot"
                >
                  <Maximize2 :size="13" aria-hidden="true" />
                </button>
              </header>
              <div v-if="snapshot.rows.length > 0" class="evidence-table-wrap">
                <table class="evidence-table">
                  <thead>
                    <tr>
                      <th v-for="column in snapshot.columns" :key="column">{{ column }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, index) in snapshot.rows.slice(0, 3)" :key="index">
                      <td
                        v-for="column in snapshot.columns"
                        :key="column"
                        :class="`tone-${formatEvidenceCell(row[column]).tone}`"
                      >
                        {{ formatEvidenceCell(row[column]).text }}
                      </td>
                    </tr>
                  </tbody>
                </table>
                <small v-if="snapshot.rows.length > 3" class="evidence-more">仅预览前 3 行</small>
              </div>
              <footer class="evidence-footer">
                共 {{ snapshot.rowCount }} 行 · {{ snapshot.durationMs }} ms
              </footer>
            </article>
          </div>
        </section>
      </aside>
    </main>

    <div v-if="evidenceDetail" class="evidence-modal-scrim" aria-hidden="true" @click="evidenceDetail = undefined" />
    <div v-if="evidenceDetail" class="evidence-modal" role="dialog" aria-modal="true" aria-label="证据快照详情">
      <header class="evidence-modal-header">
        <FileSearch :size="15" aria-hidden="true" />
        <div class="evidence-modal-title">
          <strong>{{ evidenceDetail.sourceTable }}</strong>
          <small>{{ evidenceDetail.sourceRange }} · 共 {{ evidenceDetail.rowCount }} 行 · {{ evidenceDetail.durationMs }} ms</small>
        </div>
        <button
          class="icon-button"
          type="button"
          title="关闭"
          aria-label="关闭证据详情"
          @click="evidenceDetail = undefined"
        >
          ✕
        </button>
      </header>
      <div class="evidence-modal-body">
        <table class="evidence-table">
          <thead>
            <tr>
              <th v-for="column in evidenceDetail.columns" :key="column">{{ column }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in evidenceDetail.rows" :key="index">
              <td
                v-for="column in evidenceDetail.columns"
                :key="column"
                :class="`tone-${formatEvidenceCell(row[column]).tone}`"
              >
                {{ formatEvidenceCell(row[column]).text }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="evidence-modal-footer">
        <small class="task-id">{{ evidenceDetail.evidenceSnapshotId }}</small>
      </footer>
    </div>

    <div v-if="inspectorOpen" class="inspector-scrim" aria-hidden="true" @click="inspectorOpen = false" />
    <aside
      v-if="inspectorOpen"
      class="inspector-drawer"
      role="dialog"
      aria-modal="true"
      aria-label="状态检查器"
    >
      <header>
        <strong>状态检查器</strong>
        <button
          class="icon-button"
          type="button"
          title="关闭"
          aria-label="关闭状态检查器"
          @click="inspectorOpen = false"
        >
          ✕
        </button>
      </header>
      <pre>{{ JSON.stringify(inspectorState, null, 2) }}</pre>
    </aside>
  </div>
</template>
