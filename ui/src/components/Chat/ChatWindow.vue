<template>
  <div class="flex flex-col h-full bg-surface-container-lowest">
    <div class="flex-1 overflow-hidden">
      <MessageList />
    </div>
    <MessageInput
      @submit="handleSendMessage"
      @stop="handleStop"
      @newSession="handleNewSession"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { useChatStore } from '@/stores/chat';
import { useToast } from '@/composables/useToast';
import { useChatStream } from '@/composables/useChatStream';
import MessageList from './MessageList.vue';
import MessageInput from './MessageInput.vue';

const chatStore = useChatStore();
const toast = useToast();
const { startStream, stopStream, isConnecting } = useChatStream();

const handleSendMessage = async (message: string) => {
  await startStream(message);
};

const handleStop = () => {
  stopStream();
  toast.info('已停止生成');
};

const handleNewSession = () => {
  chatStore.createNewSession();
  toast.success('已创建新会话');
};

onMounted(() => {
  if (!chatStore.currentSessionKey) {
    chatStore.createNewSession();
  }
});
</script>
