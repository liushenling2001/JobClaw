<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">Agent 配置</h1>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <!-- 基础配置 -->
      <Card class="p-6">
        <h2 class="text-lg font-bold text-on-surface mb-4 flex items-center gap-2">
          <span class="material-symbols-outlined text-primary">settings</span>
          <span>基础配置</span>
        </h2>

        <div v-if="loading" class="space-y-4">
          <Skeleton type="text" :lines="5" />
        </div>

        <div v-else class="space-y-4">
          <div>
            <label class="block text-sm text-on-surface-variant mb-2">工作空间路径</label>
            <div class="text-sm text-on-surface font-mono bg-surface-container-lowest px-3 py-2 rounded">
              {{ config.workspace }}
            </div>
          </div>

          <div>
            <label class="block text-sm text-on-surface-variant mb-2">当前模型</label>
            <div class="text-sm text-on-surface font-headline">
              {{ config.model }}
            </div>
          </div>

          <div>
            <label class="block text-sm text-on-surface-variant mb-2">最大 Token 数</label>
            <input v-model.number="configForm.maxTokens" type="number" placeholder="4096" class="w-full bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50" />
          </div>

          <div>
            <label class="block text-sm text-on-surface-variant mb-2">Temperature</label>
            <input v-model.number="configForm.temperature" type="number" step="0.1" min="0" max="2" placeholder="0.7" class="w-full bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50" />
            <p class="text-xs text-on-surface-variant mt-1">值越大输出越随机，0 为确定性输出</p>
          </div>

          <div>
            <label class="block text-sm text-on-surface-variant mb-2">最大工具迭代次数</label>
            <input v-model.number="configForm.maxToolIterations" type="number" placeholder="10" class="w-full bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50" />
          </div>
        </div>
      </Card>

      <!-- 高级配置 -->
      <Card class="p-6">
        <h2 class="text-lg font-bold text-on-surface mb-4 flex items-center gap-2">
          <span class="material-symbols-outlined text-secondary">tune</span>
          <span>高级选项</span>
        </h2>

        <div v-if="loading" class="space-y-4">
          <Skeleton type="text" :lines="3" />
        </div>

        <div v-else class="space-y-4">
          <div class="flex items-center justify-between p-3 rounded-lg bg-surface-container-high">
            <div>
              <div class="text-sm font-bold text-on-surface">心跳检测</div>
              <div class="text-xs text-on-surface-variant mt-1">定期检查 Agent 存活状态</div>
            </div>
            <label class="relative inline-flex items-center cursor-pointer">
              <input type="checkbox" v-model="configForm.heartbeatEnabled" class="sr-only peer" />
              <div class="w-11 h-6 bg-surface-container-highest peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-secondary rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-secondary"></div>
            </label>
          </div>

          <div class="flex items-center justify-between p-3 rounded-lg bg-surface-container-high">
            <div>
              <div class="text-sm font-bold text-on-surface">限制在工作空间</div>
              <div class="text-xs text-on-surface-variant mt-1">只允许访问工作空间内的文件</div>
            </div>
            <label class="relative inline-flex items-center cursor-pointer">
              <input type="checkbox" v-model="configForm.restrictToWorkspace" class="sr-only peer" />
              <div class="w-11 h-6 bg-surface-container-highest peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-secondary rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-secondary"></div>
            </label>
          </div>
        </div>
      </Card>
    </div>

    <div class="mt-6 flex justify-end gap-2">
      <Button variant="ghost" @click="resetConfig">重置</Button>
      <Button variant="primary" @click="saveConfig" :disabled="saving || loading">
        {{ saving ? '保存中...' : '保存配置' }}
      </Button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue';
import { useToast } from '@/composables/useToast';
import { agentApi } from '@/api/agent';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface AgentConfig {
  workspace: string;
  model: string;
  maxTokens: number;
  temperature: number;
  maxToolIterations: number;
  heartbeatEnabled: boolean;
  restrictToWorkspace: boolean;
}

const toast = useToast();
const loading = ref(true);
const saving = ref(false);
const config = ref<AgentConfig>({
  workspace: '',
  model: '',
  maxTokens: 4096,
  temperature: 0.7,
  maxToolIterations: 10,
  heartbeatEnabled: true,
  restrictToWorkspace: true
});

const configForm = reactive({ ...config.value });

const loadConfig = async () => {
  loading.value = true;
  try {
    const data = await agentApi.getConfig();
    config.value = data;
    Object.assign(configForm, data);
  } catch (e) {
    toast.error('加载配置失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const resetConfig = () => {
  Object.assign(configForm, config.value);
};

const saveConfig = async () => {
  saving.value = true;
  try {
    await agentApi.updateConfig({
      maxTokens: configForm.maxTokens,
      temperature: configForm.temperature,
      maxToolIterations: configForm.maxToolIterations,
      heartbeatEnabled: configForm.heartbeatEnabled,
      restrictToWorkspace: configForm.restrictToWorkspace
    });
    toast.success('配置已保存');
    Object.assign(config.value, configForm);
  } catch (e) {
    toast.error('保存失败：' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

onMounted(() => {
  loadConfig();
});
</script>
