<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">仪表盘</h1>

    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-primary/20 flex items-center justify-center border border-primary/30">
            <span class="material-symbols-outlined text-primary">hub</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ status }}</div>
            <div class="text-sm text-on-surface-variant">系统状态</div>
          </div>
        </div>
      </Card>

      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-secondary/20 flex items-center justify-center border border-secondary/30">
            <span class="material-symbols-outlined text-secondary">forum</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ sessionCount }}</div>
            <div class="text-sm text-on-surface-variant">活跃会话</div>
          </div>
        </div>
      </Card>

      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-tertiary/20 flex items-center justify-center border border-tertiary/30">
            <span class="material-symbols-outlined text-tertiary">smart_toy</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ currentModel }}</div>
            <div class="text-sm text-on-surface-variant">当前模型</div>
          </div>
        </div>
      </Card>

      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-error/20 flex items-center justify-center border border-error/30">
            <span class="material-symbols-outlined text-error">workspaces</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface truncate max-w-[120px]">{{ workspacePath }}</div>
            <div class="text-sm text-on-surface-variant">工作空间</div>
          </div>
        </div>
      </Card>
    </div>

    <Card class="p-6">
      <h2 class="text-lg font-bold text-on-surface mb-4">快捷操作</h2>
      <div class="flex gap-4">
        <router-link to="/chat">
          <Button variant="primary">新建对话</Button>
        </router-link>
        <router-link to="/sessions">
          <Button variant="secondary">查看会话</Button>
        </router-link>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';

const status = ref('Online');
const sessionCount = ref(0);
const currentModel = ref('-');
const workspacePath = ref('-');

onMounted(async () => {
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    sessionCount.value = data.sessions || 0;
    currentModel.value = data.model || '-';
    workspacePath.value = data.workspace || '-';
  } catch (e) {
    console.error('Failed to load status:', e);
    status.value = 'Offline';
  }
});
</script>
