<template>
  <div class="border-t border-outline-variant/20 p-4 bg-surface-container-low">
    <div class="flex gap-4 items-end">
      <div class="flex-1">
        <textarea
          v-model="inputMessage"
          :placeholder="isStreaming ? '正在等待响应...' : '输入消息...'"
          :disabled="isStreaming"
          rows="3"
          class="w-full bg-surface-container-high border border-outline-variant/30 rounded-lg px-4 py-3 text-on-surface text-sm resize-none focus:outline-none focus:ring-2 focus:ring-secondary/50 disabled:opacity-50 font-mono"
          @keydown.enter.exact.prevent="handleSubmit"
        ></textarea>
      </div>

      <div class="flex gap-2">
        <Button
          variant="ghost"
          @click="handleNewSession"
          :disabled="isStreaming"
          class="flex items-center gap-2"
        >
          <span class="material-symbols-outlined text-sm">add_circle</span>
          <span>新建</span>
        </Button>

        <Button
          variant="primary"
          @click="handleSubmit"
          :disabled="!inputMessage.trim() || isStreaming"
          class="flex items-center gap-2"
        >
          <span v-if="isStreaming" class="material-symbols-outlined text-sm animate-spin">progress_activity</span>
          <span v-else class="material-symbols-outlined text-sm">send</span>
          <span>{{ isStreaming ? '发送中...' : '发送' }}</span>
        </Button>

        <Button
          v-if="isStreaming"
          variant="secondary"
          @click="handleStop"
          class="flex items-center gap-2"
        >
          <span class="material-symbols-outlined text-sm">stop</span>
          <span>停止</span>
        </Button>
      </div>
    </div>

    <div v-if="currentSessionKey" class="mt-2 text-xs text-on-surface-variant flex items-center gap-2">
      <span class="material-symbols-outlined text-xs">info</span>
      <span>会话：{{ currentSessionKey }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useChatStore } from '@/stores/chat';

const chatStore = useChatStore();

const inputMessage = ref('');

const isStreaming = computed(() => chatStore.isStreaming);
const currentSessionKey = computed(() => chatStore.currentSessionKey);

const emit = defineEmits<{
  submit: [message: string];
  stop: [];
  newSession: [];
}>();

const handleSubmit = () => {
  if (!inputMessage.value.trim() || isStreaming.value) return;

  const message = inputMessage.value.trim();
  inputMessage.value = '';

  // 添加用户消息到 store
  chatStore.addMessage('user', message);

  emit('submit', message);
};

const handleStop = () => {
  emit('stop');
};

const handleNewSession = () => {
  emit('newSession');
};
</script>
