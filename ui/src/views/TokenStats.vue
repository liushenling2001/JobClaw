<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">Token 使用统计</h1>

    <div class="mb-6 flex gap-4">
      <div class="flex items-center gap-2">
        <label class="text-sm text-on-surface-variant">开始日期</label>
        <input
          type="date"
          v-model="startDate"
          class="bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50"
        />
      </div>
      <div class="flex items-center gap-2">
        <label class="text-sm text-on-surface-variant">结束日期</label>
        <input
          type="date"
          v-model="endDate"
          class="bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50"
        />
      </div>
      <Button variant="primary" @click="loadStats" class="flex items-center gap-2">
        <span class="material-symbols-outlined text-sm">refresh</span>
        <span>刷新</span>
      </Button>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-6 mb-6">
      <Card class="p-6">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-primary/20 flex items-center justify-center border border-primary/30">
            <span class="material-symbols-outlined text-primary">token</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ stats.totalTokens?.toLocaleString() || '-' }}</div>
            <div class="text-sm text-on-surface-variant">总 Token 数</div>
          </div>
        </div>
      </Card>

      <Card class="p-6">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-secondary/20 flex items-center justify-center border border-secondary/30">
            <span class="material-symbols-outlined text-secondary">input</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ stats.inputTokens?.toLocaleString() || '-' }}</div>
            <div class="text-sm text-on-surface-variant">输入 Token</div>
          </div>
        </div>
      </Card>

      <Card class="p-6">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-tertiary/20 flex items-center justify-center border border-tertiary/30">
            <span class="material-symbols-outlined text-tertiary">output</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ stats.outputTokens?.toLocaleString() || '-' }}</div>
            <div class="text-sm text-on-surface-variant">输出 Token</div>
          </div>
        </div>
      </Card>
    </div>

    <Card class="p-6 mb-6">
      <h2 class="text-lg font-bold text-on-surface mb-4">使用趋势</h2>
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="5" />
      </div>
      <div v-else-if="stats.byDate && stats.byDate.length > 0" class="space-y-2">
        <div
          v-for="day in stats.byDate"
          :key="day.date"
          class="flex items-center gap-4 p-3 rounded hover:bg-surface-container-high transition-colors"
        >
          <div class="w-32 text-sm text-on-surface font-mono">{{ day.date }}</div>
          <div class="flex-1 h-8 bg-surface-container-lowest rounded overflow-hidden">
            <div
              class="h-full bg-gradient-to-r from-primary to-secondary transition-all duration-500"
              :style="{ width: getBarWidth(day.total) + '%' }"
            ></div>
          </div>
          <div class="w-24 text-right text-sm text-on-surface font-mono">{{ day.total?.toLocaleString() || 0 }}</div>
        </div>
      </div>
      <div v-else class="flex flex-col items-center justify-center py-12 text-on-surface-variant">
        <span class="material-symbols-outlined text-6xl mb-4">bar_chart</span>
        <p>暂无数据</p>
      </div>
    </Card>

    <Card class="p-6">
      <h2 class="text-lg font-bold text-on-surface mb-4">按模型统计</h2>
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="3" />
      </div>
      <div v-else-if="stats.byModel && stats.byModel.length > 0" class="space-y-3">
        <div
          v-for="model in stats.byModel"
          :key="model.model"
          class="flex items-center justify-between p-4 rounded-lg border border-outline-variant/20"
        >
          <div>
            <div class="text-sm font-bold text-on-surface font-headline">{{ model.model }}</div>
            <div class="text-xs text-on-surface-variant mt-1">
              输入：{{ model.inputTokens?.toLocaleString() || 0 }} · 输出：{{ model.outputTokens?.toLocaleString() || 0 }}
            </div>
          </div>
          <div class="text-lg font-bold text-on-surface">{{ model.total?.toLocaleString() || 0 }}</div>
        </div>
      </div>
      <div v-else class="flex flex-col items-center justify-center py-12 text-on-surface-variant">
        <span class="material-symbols-outlined text-6xl mb-4">category</span>
        <p>暂无数据</p>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useToast } from '@/composables/useToast';
import { statsApi } from '@/api/stats';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Skeleton from '@/components/common/Skeleton.vue';

const toast = useToast();

const startDate = ref('');
const endDate = ref('');
const loading = ref(true);
const stats = reactive<{
  totalTokens?: number;
  inputTokens?: number;
  outputTokens?: number;
  byDate?: Array<{ date: string; total?: number; inputTokens?: number; outputTokens?: number }>;
  byModel?: Array<{ model: string; total?: number; inputTokens?: number; outputTokens?: number }>;
}>({});

const loadStats = async () => {
  loading.value = true;
  try {
    const params: any = {};
    if (startDate.value) params.startDate = startDate.value;
    if (endDate.value) params.endDate = endDate.value;

    const data = await statsApi.get(params);
    stats.totalTokens = data.totalTokens;
    stats.inputTokens = data.inputTokens;
    stats.outputTokens = data.outputTokens;
    stats.byDate = data.byDate || [];
    stats.byModel = data.byModel || [];
  } catch (e) {
    toast.error('加载统计数据失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const getBarWidth = (value: number): number => {
  if (!stats.byDate || stats.byDate.length === 0) return 0;
  const max = Math.max(...stats.byDate.map(d => d.total || 0));
  if (max === 0) return 0;
  return (value / max) * 100;
};

const initDates = () => {
  const end = new Date();
  const start = new Date();
  start.setDate(start.getDate() - 30);

  endDate.value = end.toISOString().split('T')[0];
  startDate.value = start.toISOString().split('T')[0];
};

onMounted(() => {
  initDates();
  loadStats();
});
</script>
