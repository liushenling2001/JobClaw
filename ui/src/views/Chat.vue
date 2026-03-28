<template>
  <div class="h-[calc(100vh-64px)] flex">
    <div class="flex-1 flex flex-col">
      <div class="h-12 border-b border-outline-variant/20 flex items-center justify-between px-4 bg-surface-container-low">
        <div class="flex items-center gap-4">
          <h2 class="text-sm font-bold text-on-surface font-headline">对话</h2>
          <span v-if="currentSessionKey" class="text-xs text-on-surface-variant font-mono">
            {{ currentSessionKey }}
          </span>
        </div>
        <div class="flex items-center gap-2">
          <span v-if="isStreaming" class="flex items-center gap-2 text-xs text-secondary">
            <span class="material-symbols-outlined text-sm animate-spin">progress_activity</span>
            <span>AI 思考中...</span>
          </span>
          <span v-else-if="isConnected" class="flex items-center gap-2 text-xs text-green-400">
            <span class="material-symbols-outlined text-sm">check_circle</span>
            <span>就绪</span>
          </span>
        </div>
      </div>

      <ChatWindow class="flex-1" />
    </div>

    <div v-if="isSidebarOpen" class="w-64 border-l border-outline-variant/20 bg-surface-container-low flex flex-col">
      <div class="h-12 border-b border-outline-variant/20 flex items-center justify-between px-4">
        <h3 class="text-sm font-bold text-on-surface font-headline">历史会话</h3>
        <button
          @click="toggleSidebar"
          class="material-symbols-outlined text-sm text-on-surface-variant hover:text-on-surface"
        >
          close
        </button>
      </div>
      <div class="flex-1 overflow-y-auto p-2 space-y-1">
        <div v-for="session in sessions" :key="session.key"
             :class="[
               'p-3 rounded cursor-pointer transition-colors',
               session.key === currentSessionKey
                 ? 'bg-primary/20 border border-primary/30'
                 : 'hover:bg-surface-container-high'
             ]"
             @click="selectSession(session)"
        >
          <div class="text-xs font-bold text-on-surface truncate">{{ session.title }}</div>
          <div class="text-xs text-on-surface-variant truncate mt-1">{{ session.preview }}</div>
          <div class="text-[10px] text-on-surface-variant mt-2 flex justify-between">
            <span>{{ session.messageCount }} 条消息</span>
            <span>{{ formatRelativeTime(session.lastUpdated) }}</span>
          </div>
        </div>
        <div v-if="sessions.length === 0" class="text-center text-on-surface-variant text-sm py-8">
          暂无历史会话
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useChatStore } from '@/stores/chat';
import { sessionsApi } from '@/api/sessions';
import ChatWindow from '@/components/Chat/ChatWindow.vue';

const chatStore = useChatStore();

const isSidebarOpen = computed(() => chatStore.isSidebarOpen);
const sessions = computed(() => chatStore.sessions);
const currentSessionKey = computed(() => chatStore.currentSessionKey);

const toggleSidebar = () => {
  chatStore.toggleSidebar();
};

const selectSession = (session: any) => {
  chatStore.currentSessionKey = session.key;
  // 从 localStorage 加载消息
  const messages = sessionsApi.getMessages(session.key);
  chatStore.messages = messages;
};

const formatRelativeTime = (timestamp: string) => {
  const date = new Date(timestamp);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));

  if (days === 0) return '今天';
  if (days === 1) return '昨天';
  if (days < 7) return `${days}天前`;
  return date.toLocaleDateString('zh-CN');
};

onMounted(async () => {
  try {
    const sessionList = await sessionsApi.list();
    chatStore.sessions = sessionList.map((s: any) => ({
      key: s.key,
      title: `会话 ${s.key.slice(-8)}`,
      preview: `${s.message_count} 条消息`,
      lastUpdated: s.updated,
      messageCount: s.message_count
    }));
  } catch (e) {
    console.error('Failed to load sessions:', e);
  }
});
</script>
