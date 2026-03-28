<template>
  <div class="p-8">
    <div class="flex items-center gap-4 mb-6">
      <Button variant="ghost" @click="goBack" class="flex items-center gap-1">
        <span class="material-symbols-outlined text-sm">arrow_back</span>
        <span>返回</span>
      </Button>
      <h1 class="text-2xl font-bold text-on-surface font-headline">会话详情</h1>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Skeleton type="text" :lines="10" />
    </div>

    <div v-else-if="error" class="flex flex-col items-center justify-center py-12 text-error">
      <span class="material-symbols-outlined text-6xl mb-4">error</span>
      <p>{{ error }}</p>
    </div>

    <div v-else-if="session" class="space-y-6">
      <Card class="p-4">
        <div class="flex items-center justify-between">
          <div>
            <h2 class="text-lg font-bold text-on-surface font-headline">{{ session.key }}</h2>
            <div class="text-sm text-on-surface-variant mt-2">
              创建时间：{{ formatDate(session.created) }} · {{ session.message_count }} 条消息
            </div>
          </div>
          <div class="flex gap-2">
            <Button variant="secondary" @click="loadMessages" class="flex items-center gap-1">
              <span class="material-symbols-outlined text-sm">refresh</span>
              <span>刷新</span>
            </Button>
            <Button variant="ghost" @click="deleteSession" class="flex items-center gap-1 text-error">
              <span class="material-symbols-outlined text-sm">delete</span>
              <span>删除</span>
            </Button>
          </div>
        </div>
      </Card>

      <Card class="p-6">
        <h3 class="text-sm font-bold text-on-surface font-headline mb-4">消息列表</h3>
        <div class="space-y-4 max-h-[60vh] overflow-y-auto">
          <div
            v-for="message in messages"
            :key="message.id"
            :class="[
              'flex gap-4 p-4 rounded-lg',
              message.role === 'user' ? 'bg-primary-container/20' : 'bg-surface-container-high'
            ]"
          >
            <div
              :class="[
                'w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0',
                message.role === 'user' ? 'bg-primary/20 border border-primary/30' : 'bg-secondary/20 border border-secondary/30'
              ]"
            >
              <span
                :class="[
                  'material-symbols-outlined text-sm',
                  message.role === 'user' ? 'text-primary' : 'text-secondary'
                ]"
              >
                {{ message.role === 'user' ? 'person' : 'smart_toy' }}
              </span>
            </div>

            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-2">
                <span class="text-xs font-bold text-on-surface font-headline">
                  {{ message.role === 'user' ? '用户' : 'AI' }}
                </span>
                <span class="text-xs text-on-surface-variant">{{ formatTime(message.timestamp) }}</span>
              </div>

              <div v-if="message.toolCall" class="mb-3">
                <div class="text-xs text-on-surface-variant mb-2">工具调用：{{ message.toolCall.toolName }}</div>
                <div
                  :class="[
                    'text-xs font-mono p-3 rounded',
                    message.toolCall.status === 'success' ? 'bg-green-500/10 text-green-400' :
                    message.toolCall.status === 'error' ? 'bg-error/10 text-error' :
                    'bg-secondary/10 text-secondary'
                  ]"
                >
                  {{ formatToolResult(message.toolCall) }}
                </div>
              </div>

              <div v-if="message.content" class="text-sm text-on-surface whitespace-pre-wrap font-mono">
                {{ message.content }}
              </div>
            </div>
          </div>

          <div v-if="messages.length === 0" class="text-center text-on-surface-variant py-8">
            暂无消息
          </div>
        </div>
      </Card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useToast } from '@/composables/useToast';
import { sessionsApi } from '@/api/sessions';
import type { Message, ToolCall } from '@/types';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Skeleton from '@/components/common/Skeleton.vue';

const route = useRoute();
const router = useRouter();
const toast = useToast();

const session = ref<any>(null);
const messages = ref<Message[]>([]);
const loading = ref(true);
const error = ref('');

const sessionKey = computed(() => route.params.id as string);

const loadSession = async () => {
  loading.value = true;
  error.value = '';
  try {
    session.value = await sessionsApi.getDetail(decodeURIComponent(sessionKey.value));
  } catch (e) {
    error.value = '加载会话详情失败：' + (e as Error).message;
    toast.error(error.value);
  } finally {
    loading.value = false;
  }
};

const loadMessages = () => {
  messages.value = sessionsApi.getMessages(decodeURIComponent(sessionKey.value));
};

const deleteSession = async () => {
  if (!confirm('确定要删除此会话吗？此操作不可恢复。')) return;

  try {
    await sessionsApi.delete(decodeURIComponent(sessionKey.value));
    toast.success('会话已删除');
    router.push('/sessions');
  } catch (e) {
    toast.error('删除失败：' + (e as Error).message);
  }
};

const goBack = () => {
  router.back();
};

const formatDate = (timestamp: string) => {
  return new Date(timestamp).toLocaleString('zh-CN');
};

const formatTime = (timestamp: string) => {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
};

const formatToolResult = (toolCall: ToolCall) => {
  if (typeof toolCall.result === 'string') return toolCall.result;
  return JSON.stringify(toolCall.result, null, 2);
};

onMounted(() => {
  loadSession();
  loadMessages();
});
</script>
