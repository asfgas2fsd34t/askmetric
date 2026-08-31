<script setup lang="ts">
import { Building2, LogOut, MessagesSquare, PanelRight, RefreshCw, UserRound } from "lucide-vue-next";
import { onMounted, ref } from "vue";

import { accessToken, logout } from "./auth";
import { loadWorkspaceSession, type WorkspaceSession } from "./session";

const session = ref<WorkspaceSession>();
const loading = ref(true);
const error = ref("");

async function refresh(requestedWorkspaceId?: string) {
  loading.value = true;
  error.value = "";
  try {
    session.value = await loadWorkspaceSession(await accessToken(), requestedWorkspaceId);
  } catch {
    error.value = "无法加载工作区";
  } finally {
    loading.value = false;
  }
}

function selectWorkspace(event: Event) {
  void refresh((event.target as HTMLSelectElement).value);
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
        </div>
        <div class="empty-list">暂无对话</div>
      </aside>

      <section class="conversation-canvas" aria-labelledby="workspace-heading">
        <div class="workspace-welcome">
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
        </dl>
      </aside>
    </main>
  </div>
</template>
