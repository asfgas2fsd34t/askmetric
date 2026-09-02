<script setup lang="ts">
import {
  Building2,
  LogOut,
  MessagesSquare,
  PanelRight,
  Plus,
  RefreshCw,
  Send,
  UserRound,
} from "lucide-vue-next";
import { computed, onMounted, ref } from "vue";

import { accessToken, logout } from "./auth";
import {
  createConversation,
  createMessage,
  loadConversation,
  loadConversations,
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
const latestAgentRun = computed(() => activeConversation.value?.agentRuns.at(-1));
const activeAnalysisTask = computed(() =>
  activeConversation.value?.analysisTasks.find((task) => task.status === "active"),
);

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
  activeConversation.value = undefined;
  conversations.value = [];
  void refresh((event.target as HTMLSelectElement).value);
}

async function refreshConversations(workspaceId: string, preferredConversationId?: string) {
  conversationLoading.value = true;
  conversationError.value = "";
  try {
    const token = await accessToken();
    conversations.value = await loadConversations(token, workspaceId);
    const conversationId = preferredConversationId ?? conversations.value[0]?.conversationId;
    activeConversation.value = conversationId
      ? await loadConversation(token, workspaceId, conversationId)
      : undefined;
  } catch {
    conversationError.value = "无法加载对话";
  } finally {
    conversationLoading.value = false;
  }
}

async function openConversation(conversationId: string) {
  if (!session.value || conversationId === activeConversation.value?.conversationId) return;
  conversationLoading.value = true;
  conversationError.value = "";
  try {
    activeConversation.value = await loadConversation(
      await accessToken(),
      session.value.currentMembership.workspaceId,
      conversationId,
    );
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
    activeConversation.value = {
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
    };
    draft.value = "";
    await refreshConversations(workspaceId, conversationId);
  } catch {
    conversationError.value = "消息发送失败";
  } finally {
    sending.value = false;
  }
}

onMounted(() => refresh());
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
          <div v-if="activeConversation.agentRuns.length" class="agent-run-timeline" aria-label="Agent 运行记录">
            <article v-for="run in activeConversation.agentRuns" :key="run.runId" class="agent-run">
              <div>
                <strong>{{ run.auditEvents.at(-1)?.message ?? "等待 Agent 事件" }}</strong>
                <span>{{ run.intentRoute }} · {{ Math.round(run.intentConfidence * 100) }}%</span>
              </div>
              <ul class="agent-run-audit-events" aria-label="运行审计事件">
                <li v-for="event in run.auditEvents" :key="event.eventId">
                  <span>{{ event.eventType }}</span>
                  <small>{{ event.message }}</small>
                </li>
              </ul>
            </article>
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

      <aside class="context-panel" aria-labelledby="context-heading">
        <div class="panel-heading">
          <PanelRight :size="17" aria-hidden="true" />
          <h2 id="context-heading">当前上下文</h2>
        </div>
        <h3>对话</h3>
        <dl>
          <div>
            <dt>工作区</dt>
            <dd>{{ session.currentMembership.workspaceName }}</dd>
          </div>
          <div>
            <dt>成员关系</dt>
            <dd>{{ session.currentMembership.membershipId }}</dd>
          </div>
          <div>
            <dt>当前用户</dt>
            <dd>{{ session.user.username }}</dd>
          </div>
          <div v-if="activeConversation">
            <dt>标识</dt>
            <dd>{{ activeConversation.conversationId }}</dd>
          </div>
        </dl>
        <section v-if="latestAgentRun" class="context-section" aria-labelledby="agent-run-heading">
          <h3 id="agent-run-heading">Agent 运行</h3>
          <dl>
            <div>
              <dt>标识</dt>
              <dd>{{ latestAgentRun.runId }}</dd>
            </div>
            <div>
              <dt>意图路由</dt>
              <dd>{{ latestAgentRun.intentRoute }}</dd>
            </div>
            <div>
              <dt>置信度</dt>
              <dd>{{ Math.round(latestAgentRun.intentConfidence * 100) }}%</dd>
            </div>
          </dl>
        </section>
        <section v-if="activeAnalysisTask" class="context-section" aria-labelledby="analysis-task-heading">
          <h3 id="analysis-task-heading">分析任务</h3>
          <dl>
            <div>
              <dt>目标</dt>
              <dd>{{ activeAnalysisTask.goal }}</dd>
            </div>
            <div>
              <dt>状态</dt>
              <dd>{{ activeAnalysisTask.status }}</dd>
            </div>
            <div>
              <dt>标识</dt>
              <dd>{{ activeAnalysisTask.analysisTaskId }}</dd>
            </div>
            <div>
              <dt>来源 Agent 运行</dt>
              <dd>{{ activeAnalysisTask.sourceAgentRunId }}</dd>
            </div>
          </dl>
        </section>
        <section
          v-if="activeConversation && activeConversation.analysisTasks.length"
          class="context-section"
          aria-labelledby="analysis-tasks-heading"
        >
          <h3 id="analysis-tasks-heading">分析任务列表</h3>
          <ol class="analysis-task-list">
            <li
              v-for="task in activeConversation.analysisTasks"
              :key="task.analysisTaskId"
              :aria-current="task.status === 'active' ? 'true' : undefined"
            >
              <strong>{{ task.status === "active" ? "当前活动" : task.status }}</strong>
              <span>{{ task.goal }}</span>
            </li>
          </ol>
        </section>
      </aside>
    </main>
  </div>
</template>
