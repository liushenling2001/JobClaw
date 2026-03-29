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
      </div>

      <div class="flex items-center gap-4">
        <span v-if="toolCall.duration" class="text-xs text-on-surface-variant font-mono">
          {{ toolCall.duration > 0 ? (toolCall.duration / 1000).toFixed(2) + 's' : '' }}
        </span>
        <span class="material-symbols-outlined text-sm text-on-surface-variant transition-transform"
              :class="{ 'rotate-180': expanded }">
          expand_more
        </span>
      </div>
    </div>

    <div v-if="expanded" class="border-t border-outline-variant/20">
      <!-- 参数部分 -->
      <div v-if="toolCall.parameters" class="p-3 border-b border-outline-variant/10">
        <div class="text-xs text-on-surface-variant uppercase tracking-wider mb-2 flex items-center gap-2">
          <span class="material-symbols-outlined text-xs">input</span>
          <span>参数</span>
        </div>
        <pre class="bg-surface-container-lowest rounded p-3 text-xs text-on-surface font-mono overflow-x-auto">{{ formatJson(toolCall.parameters) }}</pre>
      </div>

      <!-- 结果部分 -->
      <div v-if="toolCall.result !== null && toolCall.result !== undefined" class="p-3">
        <div class="text-xs text-on-surface-variant uppercase tracking-wider mb-2 flex items-center gap-2">
          <span class="material-symbols-outlined text-xs">output</span>
          <span>结果</span>
          <span v-if="toolCall.status === 'success'" class="text-green-400 text-xs">(成功)</span>
          <span v-if="toolCall.status === 'error'" class="text-error text-xs">(失败)</span>
        </div>
        <pre :class="[
          'rounded p-3 text-xs font-mono overflow-x-auto whitespace-pre-wrap break-words',
          toolCall.status === 'error' ? 'bg-error/10 text-error' : 'bg-surface-container-lowest text-on-surface'
        ]">{{ formatResult(toolCall.result) }}</pre>
      </div>

      <!-- 执行中状态 -->
      <div v-if="toolCall.status === 'running'" class="p-3 flex items-center gap-2 text-xs text-secondary">
        <span class="material-symbols-outlined text-sm animate-spin">progress_activity</span>
        <span>执行中...</span>
      </div>
    </div>

    <!-- 收起时的简要状态 -->
    <div v-else class="border-t border-outline-variant/20 px-4 py-2 text-xs text-on-surface-variant">
      <span v-if="toolCall.status === 'running'">正在执行...</span>
      <span v-else-if="toolCall.status === 'success'">
        <span class="text-green-400">✓ 已完成</span>
        <span v-if="getResultPreview()" class="ml-2 opacity-70">{{ getResultPreview() }}</span>
      </span>
      <span v-else-if="toolCall.status === 'error'">
        <span class="text-error">✗ 执行失败</span>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { ToolCall } from '@/types';

const props = defineProps<{
  toolCall: ToolCall;
}>();

// 默认收起，点击展开
const expanded = ref(false);

const toggleExpand = () => {
  expanded.value = !expanded.value;
};

// 获取结果的预览（用于收起时显示）
const getResultPreview = () => {
  if (!props.toolCall.result) return '';
  const result = props.toolCall.result;
  if (typeof result === 'string') {
    // 如果是 JSON 字符串，尝试解析并提取关键信息
    try {
      const parsed = JSON.parse(result);
      if (typeof parsed === 'object') {
        const keys = Object.keys(parsed);
        return `${keys.length} 个字段`;
      }
      return String(result).slice(0, 50);
    } catch {
      return result.slice(0, 50);
    }
  }
  return '';
};

const formatJson = (data: any): string => {
  if (typeof data === 'string') {
    try {
      const parsed = JSON.parse(data);
      return JSON.stringify(parsed, null, 2);
    } catch {
      return data;
    }
  }
  if (typeof data === 'object') {
    return JSON.stringify(data, null, 2);
  }
  return String(data);
};

const formatResult = (result: any): string => {
  if (typeof result === 'string') {
    // 尝试解析 JSON 并格式化
    try {
      const parsed = JSON.parse(result);
      return JSON.stringify(parsed, null, 2);
    } catch {
      // 不是 JSON，直接返回
      return result;
    }
  }
  return JSON.stringify(result, null, 2);
};
</script>
