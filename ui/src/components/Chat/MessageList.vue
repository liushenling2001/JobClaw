<template>
  <div class="flex flex-col h-full">
    <div ref="scrollContainer" class="flex-1 overflow-y-auto p-6 space-y-4">
      <div v-if="messages.length === 0" class="flex items-center justify-center h-full">
        <div class="text-center">
          <span class="material-symbols-outlined text-6xl text-outline-variant mb-4">chat</span>
          <p class="text-on-surface-variant">开始一次新的对话</p>
        </div>
      </div>

      <div
        v-for="message in messages"
        :key="message.id"
        :class="[
          'flex gap-4',
          message.role === 'user' ? 'flex-row-reverse' : ''
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

        <div
          :class="[
            'max-w-[70%] rounded-lg p-4',
            message.role === 'user' ? 'bg-primary-container/20 border border-primary/20' : 'bg-surface-container-high border border-outline-variant/20'
          ]"
        >
          <div v-if="message.toolCall" class="mb-3">
            <ToolCallPanel :tool-call="message.toolCall" />
          </div>

          <div v-if="message.content" class="text-on-surface text-sm whitespace-pre-wrap font-mono">
            {{ message.content }}
          </div>

          <div class="text-xs text-on-surface-variant mt-2">
            {{ formatTime(message.timestamp) }}
          </div>
        </div>
      </div>

      <div v-if="isStreaming" class="flex gap-4">
        <div class="w-8 h-8 rounded-full bg-secondary/20 border border-secondary/30 flex items-center justify-center">
          <span class="material-symbols-outlined text-sm text-secondary">smart_toy</span>
        </div>
        <div class="flex items-center gap-2 text-on-surface-variant">
          <span class="terminal-cursor animate-pulse"></span>
          <span>思考中...</span>
        </div>
      </div>

      <div ref="bottomAnchor"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useChatStore } from '@/stores/chat';
import ToolCallPanel from './ToolCallPanel.vue';

const chatStore = useChatStore();

const messages = computed(() => chatStore.messages);
const isStreaming = computed(() => chatStore.isStreaming);
const scrollContainer = ref<HTMLElement | null>(null);
const bottomAnchor = ref<HTMLElement | null>(null);

const scrollToBottom = () => {
  nextTick(() => {
    if (bottomAnchor.value) {
      bottomAnchor.value.scrollIntoView({ behavior: 'smooth', block: 'end' });
      return;
    }

    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight;
    }
  });
};

watch(
  () => ({
    count: messages.value.length,
    lastMessage: messages.value[messages.value.length - 1]?.content ?? '',
    streaming: isStreaming.value
  }),
  scrollToBottom,
  { deep: true }
);

onMounted(() => {
  scrollToBottom();
});

const formatTime = (timestamp: string) => {
  const date = new Date(timestamp);
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
};
</script>