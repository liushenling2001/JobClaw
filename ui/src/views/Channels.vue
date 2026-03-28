<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">通道管理</h1>

    <Card class="p-6">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="5" />
      </div>

      <div v-else class="space-y-4">
        <div
          v-for="channel in channels"
          :key="channel.name"
          class="flex items-center justify-between p-4 rounded-lg border border-outline-variant/20 hover:bg-surface-container-high transition-colors"
        >
          <div class="flex items-center gap-4">
            <div :class="[
              'w-12 h-12 rounded-lg flex items-center justify-center border',
              channel.enabled ? 'bg-secondary/20 border-secondary/30' : 'bg-surface-container-high border-outline-variant/30'
            ]">
              <span :class="[
                'material-symbols-outlined text-lg',
                channel.enabled ? 'text-secondary' : 'text-outline-variant'
              ]">
                {{ getChannelIcon(channel.name) }}
              </span>
            </div>
            <div>
              <div class="text-sm font-bold text-on-surface font-headline capitalize">{{ channel.name }}</div>
              <div :class="[
                'text-xs mt-1 flex items-center gap-1',
                channel.enabled ? 'text-green-400' : 'text-outline-variant'
              ]">
                <span :class="channel.enabled ? '' : 'opacity-50'">
                  {{ channel.enabled ? '已启用' : '已禁用' }}
                </span>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-2">
            <Button variant="ghost" @click="viewDetail(channel.name)" class="flex items-center gap-1">
              <span class="material-symbols-outlined text-sm">edit</span>
              <span>配置</span>
            </Button>
            <label class="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                :checked="channel.enabled"
                @change="toggleChannel(channel.name, $event)"
                class="w-4 h-4 rounded border-outline-variant bg-surface-container-high text-secondary focus:ring-secondary focus:ring-offset-surface-container-low"
              />
              <span class="text-sm text-on-surface">启用</span>
            </label>
          </div>
        </div>
      </div>
    </Card>

    <!-- 配置模态框 -->
    <Modal v-if="selectedChannel" :model-value="!!selectedChannel" @update:model-value="closeModal">
      <div class="space-y-4">
        <h3 class="text-lg font-bold text-on-surface font-headline capitalize">{{ selectedChannel.name }} 配置</h3>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">Token</label>
          <Input v-model="selectedChannel.token" type="password" placeholder="输入 API Token" />
        </div>

        <div v-if="selectedChannel.allowFrom && selectedChannel.allowFrom.length > 0">
          <label class="block text-sm text-on-surface-variant mb-2">允许的 IP 地址</label>
          <div class="space-y-2">
            <div v-for="(_ip, index) in selectedChannel.allowFrom" :key="index" class="flex gap-2">
              <Input v-model="selectedChannel.allowFrom[index]" type="text" placeholder="IP 地址" />
              <Button variant="ghost" @click="removeAllowedIp(index)" class="text-error">
                <span class="material-symbols-outlined text-sm">delete</span>
              </Button>
            </div>
            <Button variant="ghost" @click="addAllowedIp" class="text-secondary">
              <span class="material-symbols-outlined text-sm">add</span>
              <span>添加 IP</span>
            </Button>
          </div>
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
import { channelsApi } from '@/api/channels';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Input from '@/components/common/Input.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface Channel {
  name: string;
  enabled: boolean;
  token?: string;
  allowFrom?: string[];
}

const toast = useToast();
const channels = ref<Channel[]>([]);
const loading = ref(true);
const selectedChannel = ref<Channel | null>(null);
const saving = ref(false);

const loadChannels = async () => {
  loading.value = true;
  try {
    channels.value = await channelsApi.list();
  } catch (e) {
    toast.error('加载通道列表失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const getChannelIcon = (name: string): string => {
  const icons: Record<string, string> = {
    telegram: 'send',
    discord: 'chat',
    feishu: 'business_chat',
    dingtalk: 'business_chat',
    qq: 'chat',
    whatsapp: 'chat',
    maixcam: 'camera_alt'
  };
  return icons[name] || 'settings_input_component';
};

const viewDetail = async (name: string) => {
  try {
    selectedChannel.value = await channelsApi.get(name);
  } catch (e) {
    toast.error('加载通道配置失败：' + (e as Error).message);
  }
};

const closeModal = () => {
  selectedChannel.value = null;
};

const addAllowedIp = () => {
  if (selectedChannel.value) {
    if (!selectedChannel.value.allowFrom) {
      selectedChannel.value.allowFrom = [];
    }
    selectedChannel.value.allowFrom.push('');
  }
};

const removeAllowedIp = (index: number) => {
  if (selectedChannel.value?.allowFrom) {
    selectedChannel.value.allowFrom.splice(index, 1);
  }
};

const toggleChannel = async (name: string, event: Event) => {
  const enabled = (event.target as HTMLInputElement).checked;
  try {
    await channelsApi.update(name, { enabled });
    toast.success(`通道已${enabled ? '启用' : '禁用'}`);
    await loadChannels();
  } catch (e) {
    toast.error('操作失败：' + (e as Error).message);
    // 恢复状态
    await loadChannels();
  }
};

const saveConfig = async () => {
  if (!selectedChannel.value) return;

  saving.value = true;
  try {
    await channelsApi.update(selectedChannel.value.name, {
      enabled: selectedChannel.value.enabled,
      token: selectedChannel.value.token,
      allowFrom: selectedChannel.value.allowFrom
    });
    toast.success('配置已保存');
    closeModal();
    await loadChannels();
  } catch (e) {
    toast.error('保存失败：' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

onMounted(() => {
  loadChannels();
});
</script>
