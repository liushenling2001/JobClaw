<template>
  <div :class="[
    'rounded-lg border overflow-hidden',
    toolCall.status === 'running' ? 'border-secondary/30 bg-secondary/5' : '',
    toolCall.status === 'success' ? 'border-green-500/30 bg-green-500/5' : '',
    toolCall.status === 'error' ? 'border-error/30 bg-error/5' : ''
  ]">
    <div
      class="flex items-center justify-between px-4 py-2 cursor-pointer hover:bg-white/5 transition-colors"
      @click="toggleExpand"
    >
      <div class="flex items-center gap-3">
        <span :class="[
          'material-symbols-outlined text-sm',
          toolCall.status === 'running' ? 'text-secondary animate-spin' : '',
          toolCall.status === 'success' ? 'text-green-400' : '',
          toolCall.status === 'error' ? 'text-error' : ''
        ]">
          {{ toolCall.status === 'running' ? 'sync' : toolCall.status === 'success' ? 'check_circle' : 'error' }}
        </span>
        <span class="text-sm font-bold text-on-surface font-headline">{{ toolCall.toolName }}</span>
        <span class="text-xs text-on-surface-variant font-mono">{{ toolCall.toolId }}</span>
      </div>

      <div class="flex items-center gap-4">
        <span v-if="toolCall.duration" class="text-xs text-on-surface-variant font-mono">
          {{ (toolCall.duration / 1000).toFixed(2) }}s
        </span>
        <span class="material-symbols-outlined text-sm text-on-surface-variant transition-transform"
              :class="{ 'rotate-180': expanded }">
          expand_more
        </span>
      </div>
    </div>

    <div v-if="expanded" class="border-t border-outline-variant/20 p-4 space-y-3">
      <div v-if="toolCall.parameters" class="space-y-1">
        <div class="text-xs text-on-surface-variant uppercase tracking-wider">参数</div>
        <pre class="bg-surface-container-lowest rounded p-3 text-xs text-on-surface font-mono overflow-x-auto">{{ formatJson(toolCall.parameters) }}</pre>
      </div>

      <div v-if="toolCall.result !== null && toolCall.result !== undefined" class="space-y-1">
        <div class="text-xs text-on-surface-variant uppercase tracking-wider">结果</div>
        <pre :class="[
          'bg-surface-container-lowest rounded p-3 text-xs font-mono overflow-x-auto',
          toolCall.status === 'error' ? 'text-error' : 'text-on-surface'
        ]">{{ formatResult(toolCall.result) }}</pre>
      </div>

      <div v-if="toolCall.status === 'running'" class="flex items-center gap-2 text-xs text-secondary">
        <span class="material-symbols-outlined text-sm animate-spin">progress_activity</span>
        <span>执行中...</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { ToolCall } from '@/types';

defineProps<{
  toolCall: ToolCall;
}>();

const expanded = ref(true);

const toggleExpand = () => {
  expanded.value = !expanded.value;
};

const formatJson = (data: any): string => {
  if (typeof data === 'string') {
    try {
      return JSON.stringify(JSON.parse(data), null, 2);
    } catch {
      return data;
    }
  }
  return JSON.stringify(data, null, 2);
};

const formatResult = (result: any): string => {
  if (typeof result === 'string') return result;
  return JSON.stringify(result, null, 2);
};
</script>

<style scoped>
.rotate-180 {
  transform: rotate(180deg);
}
</style>
