<script setup lang="ts">
import {
  Building2,
  CircleDashed,
  LogOut,
  MessagesSquare,
  Plus,
  RefreshCw,
  Send,
  UserRound,
} from "lucide-vue-next";
import { computed, onMounted, onUnmounted, ref } from "vue";

import { accessToken, logout } from "./auth";
import {
  createConversation,
  createMessage,
  loadConversation,
  loadConversations,
  watchAgentRun,
  type AgentRunEvent,
  type ConversationSnapshot,
  type ConversationSummary,
} from "./conversation";
import { loadWorkspaceSession, type WorkspaceSession } from "./session";

const session = ref<WorkspaceSession>();
const conversations = ref<ConversationSummary[]>([]);
const activeConversation = ref<ConversationSnapshot>();
const loading = ref(true);
const conversationLoading = ref(false);
const sending = ref(false);
const error = ref("");
const conversationError = ref("");
const draft = ref("");
const runSubscriptions = new Map<string, () => void>();
const activeAgentRuns = computed(() => activeConversation.value?.agentRuns.filter((run) => !runIsTerminal(run)) ?? []);
const activeAgentRun = computed(() => activeAgentRuns.value.at(-1));

function stopRunSubscriptions() {
  runSubscriptions.forEach((stop) => stop());
  runSubscriptions.clear();
}

function runIsTerminal(run: NonNullable<typeof activeConversation.value>["agentRuns"][number]) {
  const type = run.auditEvents.at(-1)?.eventType;
  return type === "agent.run.completed" || type === "agent.run.failed";
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

  if (event.eventType === "agent.run.completed" || event.eventType === "agent.run.failed") {
    runSubscriptions.get(event.runId)?.();
    runSubscriptions.delete(event.runId);
    if (session.value) {
      void refreshCompletedRun(session.value.currentMembership.workspaceId, snapshot.conversationId);
    }
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
  } catch {
    error.value = "无法加载工作区";
  } finally {
    loading.value = false;
  }
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

onMounted(() => refresh());
onUnmounted(stopRunSubscriptions);
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
    </header>

    <main class="workspace-grid">
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
          </div>
          <form class="message-composer" @submit.prevent="submitMessage">
            <label class="sr-only" for="message-draft">消息</label>
            <textarea
              id="message-draft"
              v-model="draft"
              maxlength="4000"
              rows="2"
              placeholder="输入消息"
              :disabled="sending"
              @keydown.ctrl.enter="submitMessage"
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
    </main>
  </div>
</template>
