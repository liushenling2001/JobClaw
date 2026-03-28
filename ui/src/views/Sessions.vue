<template>
  <div class="p-8">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-on-surface font-headline">会话管理</h1>
      <Button variant="primary" @click="handleNewChat" class="flex items-center gap-2">
        <span class="material-symbols-outlined text-sm">add</span>
        <span>新建对话</span>
      </Button>
    </div>

    <Card class="p-6">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="5" />
      </div>

      <div v-else-if="sessions.length === 0" class="flex flex-col items-center justify-center py-12 text-on-surface-variant">
        <span class="material-symbols-outlined text-6xl mb-4">forum</span>
        <p>暂无历史会话</p>
      </div>

      <div v-else class="space-y-3">
        <div
          v-for="session in sessions"
          :key="session.key"
          class="flex items-center justify-between p-4 rounded-lg border border-outline-variant/20 hover:bg-surface-container-high transition-colors"
        >
          <div class="flex items-center gap-4 flex-1 min-w-0">
            <div class="w-10 h-10 rounded-lg bg-secondary/20 flex items-center justify-center border border-secondary/30 flex-shrink-0">
              <span class="material-symbols-outlined text-secondary text-sm">chat</span>
            </div>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-bold text-on-surface font-headline truncate">{{ session.key }}</div>
              <div class="text-xs text-on-surface-variant mt-1">
                {{ session.message_count }} 条消息 · 更新于 {{ formatRelativeTime(session.updated) }}
              </div>
            </div>
          </div>

          <div class="flex items-center gap-2">
            <Button variant="ghost" @click="viewDetail(session.key)" class="flex items-center gap-1">
              <span class="material-symbols-outlined text-sm">visibility</span>
              <span>查看</span>
            </Button>
            <Button variant="ghost" @click="deleteSession(session.key)" class="flex items-center gap-1 text-error hover:text-error">
              <span class="material-symbols-outlined text-sm">delete</span>
              <span>删除</span>
            </Button>
          </div>
        </div>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useToast } from '@/composables/useToast';
import { sessionsApi } from '@/api/sessions';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface SessionInfo {
  key: string;
  created: string;
  updated: string;
  message_count: number;
}

const router = useRouter();
const toast = useToast();

const sessions = ref<SessionInfo[]>([]);
const loading = ref(true);

const loadSessions = async () => {
  loading.value = true;
  try {
    sessions.value = await sessionsApi.list();
  } catch (e) {
    toast.error('加载会话列表失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const handleNewChat = () => {
  router.push('/chat');
};

const viewDetail = (key: string) => {
  router.push(`/sessions/${encodeURIComponent(key)}`);
};

const deleteSession = async (key: string) => {
  if (!confirm(`确定要删除会话 "${key}" 吗？此操作不可恢复。`)) {
    return;
  }

  try {
    await sessionsApi.delete(key);
    toast.success('会话已删除');
    await loadSessions();
  } catch (e) {
    toast.error('删除失败：' + (e as Error).message);
  }
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

onMounted(() => {
  loadSessions();
});
</script>
