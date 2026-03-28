<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">模型管理</h1>

    <div class="mb-6">
      <Card class="p-4">
        <div class="flex items-center justify-between">
          <div>
            <div class="text-sm text-on-surface-variant mb-1">当前使用模型</div>
            <div class="text-xl font-bold text-on-surface font-headline">{{ currentModel }}</div>
          </div>
          <Button variant="primary" @click="showChangeModel = true" class="flex items-center gap-2">
            <span class="material-symbols-outlined text-sm">swap_horiz</span>
            <span>切换模型</span>
          </Button>
        </div>
      </Card>
    </div>

    <Card class="p-6">
      <h2 class="text-lg font-bold text-on-surface mb-4">可用模型</h2>

      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="5" />
      </div>

      <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div
          v-for="model in models"
          :key="model.name"
          :class="[
            'p-4 rounded-lg border transition-colors',
            currentModel === model.name
              ? 'bg-primary/20 border-primary/30'
              : 'bg-surface-container-high border-outline-variant/20 hover:bg-surface-container-highest'
          ]"
        >
          <div class="flex items-start justify-between">
            <div class="flex-1">
              <div class="flex items-center gap-2">
                <h3 class="text-sm font-bold text-on-surface font-headline">{{ model.name }}</h3>
                <span :class="[
                  'text-xs px-2 py-0.5 rounded',
                  model.authorized ? 'bg-green-500/20 text-green-400' : 'bg-error/20 text-error'
                ]">
                  {{ model.authorized ? '已授权' : '未授权' }}
                </span>
              </div>
              <div class="text-xs text-on-surface-variant mt-2">
                <div>提供商：{{ model.provider }}</div>
                <div v-if="model.maxContextSize">上下文：{{ formatContextSize(model.maxContextSize) }}</div>
              </div>
              <div v-if="model.description" class="text-xs text-on-surface-variant mt-2 line-clamp-2">
                {{ model.description }}
              </div>
            </div>

            <Button
              v-if="model.authorized && currentModel !== model.name"
              variant="ghost"
              @click="selectModel(model.name)"
              class="flex items-center gap-1"
            >
              <span class="material-symbols-outlined text-sm">check_circle</span>
              <span>使用</span>
            </Button>
            <span v-else-if="currentModel === model.name" class="text-green-400 text-sm flex items-center gap-1">
              <span class="material-symbols-outlined text-sm">check_circle</span>
              <span>使用中</span>
            </span>
          </div>
        </div>
      </div>
    </Card>

    <!-- 切换模型模态框 -->
    <Modal v-if="showChangeModel" :model-value="showChangeModel" @update:model-value="showChangeModel = false">
      <div class="space-y-4">
        <h3 class="text-lg font-bold text-on-surface font-headline">选择模型</h3>

        <div class="max-h-[60vh] overflow-y-auto space-y-2">
          <div
            v-for="model in models"
            :key="model.name"
            :class="[
              'p-3 rounded-lg border cursor-pointer transition-colors',
              currentModel === model.name
                ? 'bg-primary/20 border-primary/30'
                : 'bg-surface-container-high border-outline-variant/20 hover:bg-surface-container-highest'
            ]"
            @click="selectModel(model.name)"
          >
            <div class="flex items-center justify-between">
              <div>
                <div class="text-sm font-bold text-on-surface">{{ model.name }}</div>
                <div class="text-xs text-on-surface-variant">{{ model.provider }}</div>
              </div>
              <span v-if="currentModel === model.name" class="text-green-400 text-xs flex items-center gap-1">
                <span class="material-symbols-outlined text-sm">check_circle</span>
                <span>使用中</span>
              </span>
            </div>
          </div>
        </div>

        <div class="flex justify-end pt-2">
          <Button variant="ghost" @click="showChangeModel = false">关闭</Button>
        </div>
      </div>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useToast } from '@/composables/useToast';
import { modelsApi } from '@/api/models';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface Model {
  name: string;
  provider: string;
  model: string;
  maxContextSize?: number;
  description?: string;
  authorized: boolean;
}

const toast = useToast();
const models = ref<Model[]>([]);
const currentModel = ref('-');
const loading = ref(true);
const showChangeModel = ref(false);

const loadModels = async () => {
  loading.value = true;
  try {
    const [modelsRes, currentRes] = await Promise.all([
      modelsApi.list(),
      modelsApi.getCurrent()
    ]);
    models.value = modelsRes;
    currentModel.value = currentRes.model || '-';
  } catch (e) {
    toast.error('加载模型列表失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const selectModel = async (modelName: string) => {
  try {
    await modelsApi.update(modelName);
    toast.success('模型已切换');
    currentModel.value = modelName;
    showChangeModel.value = false;
  } catch (e) {
    toast.error('切换失败：' + (e as Error).message);
  }
};

const formatContextSize = (size: number): string => {
  if (size >= 1000000) {
    return (size / 1000000).toFixed(1) + 'M';
  }
  if (size >= 1000) {
    return (size / 1000).toFixed(1) + 'K';
  }
  return size.toString();
};

onMounted(() => {
  loadModels();
});
</script>
