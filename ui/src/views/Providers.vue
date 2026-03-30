<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">LLM Provider</h1>

    <Card class="p-6">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="5" />
      </div>

      <div v-else class="space-y-4">
        <div
          v-for="provider in providers"
          :key="provider.name"
          class="flex items-center justify-between p-4 rounded-lg border border-outline-variant/20 hover:bg-surface-container-high transition-colors"
        >
          <div class="flex items-center gap-4">
            <div :class="[
              'w-12 h-12 rounded-lg flex items-center justify-center border',
              provider.authorized ? 'bg-primary/20 border-primary/30' : 'bg-surface-container-high border-outline-variant/30'
            ]">
              <span :class="[
                'material-symbols-outlined text-lg',
                provider.authorized ? 'text-primary' : 'text-outline-variant'
              ]">
                {{ provider.authorized ? 'verified_user' : 'lock' }}
              </span>
            </div>
            <div>
              <div class="text-sm font-bold text-on-surface font-headline capitalize">{{ provider.name }}</div>
              <div class="text-xs text-on-surface-variant mt-1 font-mono">{{ provider.apiBase }}</div>
              <div :class="[
                'text-xs mt-1',
                provider.authorized ? 'text-green-400' : 'text-error'
              ]">
                {{ provider.authorized ? '已授权' : '未授权' }}
              </div>
            </div>
          </div>

          <div class="flex items-center gap-2">
            <Button variant="ghost" @click="viewDetail(provider.name)" class="flex items-center gap-1">
              <span class="material-symbols-outlined text-sm">edit</span>
              <span>配置</span>
            </Button>
          </div>
        </div>
      </div>
    </Card>

    <Modal v-if="selectedProvider" :model-value="!!selectedProvider" @update:model-value="closeModal">
      <div class="space-y-4">
        <h3 class="text-lg font-bold text-on-surface font-headline capitalize">{{ selectedProvider.name }} 配置</h3>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">API Base URL</label>
          <Input v-model="selectedProvider.apiBase" type="text" placeholder="https://api.example.com" />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">API Key</label>
          <Input v-model="selectedProvider.apiKey" type="password" placeholder="输入 API Key" />
          <p class="text-xs text-on-surface-variant mt-1">
            {{ selectedProvider.apiKey && selectedProvider.apiKey !== '****' ? '已设置 API Key' : '未设置 API Key' }}
          </p>
        </div>

        <div class="flex justify-end gap-2 pt-4">
          <Button variant="ghost" @click="closeModal">取消</Button>
          <Button variant="primary" @click="saveConfig" :disabled="saving">
            {{ saving ? '保存中...' : '保存' }}
          </Button>
        </div>
      </div>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useToast } from '@/composables/useToast';
import { providersApi } from '@/api/providers';
import type { Provider } from '@/types';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Input from '@/components/common/Input.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

const toast = useToast();
const providers = ref<Provider[]>([]);
const loading = ref(true);
const selectedProvider = ref<Provider | null>(null);
const saving = ref(false);

const loadProviders = async () => {
  loading.value = true;
  try {
    providers.value = await providersApi.list();
  } catch (e) {
    toast.error('加载 Provider 列表失败: ' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const viewDetail = async (name: string) => {
  try {
    selectedProvider.value = await providersApi.get(name);
  } catch (e) {
    toast.error('加载 Provider 配置失败: ' + (e as Error).message);
  }
};

const closeModal = () => {
  selectedProvider.value = null;
};

const saveConfig = async () => {
  if (!selectedProvider.value) return;

  saving.value = true;
  try {
    await providersApi.update(selectedProvider.value.name, {
      apiBase: selectedProvider.value.apiBase,
      apiKey: selectedProvider.value.apiKey
    });
    toast.success('配置已保存');
    closeModal();
    await loadProviders();
  } catch (e) {
    toast.error('保存失败: ' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

onMounted(() => {
  loadProviders();
});
</script>