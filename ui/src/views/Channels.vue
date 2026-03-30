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

    <Modal v-if="selectedChannel" :model-value="!!selectedChannel" @update:model-value="closeModal">
      <div class="space-y-4 max-h-[75vh] overflow-y-auto pr-1">
        <h3 class="text-lg font-bold text-on-surface font-headline capitalize">{{ selectedChannel.name }} 配置</h3>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">启用状态</label>
          <label class="flex items-center gap-2 cursor-pointer text-sm text-on-surface">
            <input
              v-model="selectedChannel.enabled"
              type="checkbox"
              class="w-4 h-4 rounded border-outline-variant bg-surface-container-high text-secondary focus:ring-secondary"
            />
            <span>启用该通道</span>
          </label>
        </div>

        <div v-for="field in editableFields" :key="field.key">
          <label class="block text-sm text-on-surface-variant mb-2">{{ field.label }}</label>

          <select
            v-if="field.type === 'select'"
            v-model="selectedChannel[field.key]"
            class="bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-1 focus:ring-secondary/50 w-full"
          >
            <option v-for="option in field.options || []" :key="option" :value="option">{{ option }}</option>
          </select>

          <Input
            v-else
            v-model="selectedChannel[field.key]"
            :type="field.type === 'number' ? 'number' : field.secret ? 'password' : 'text'"
            :placeholder="field.placeholder"
          />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">允许来源</label>
          <div class="space-y-2">
            <div v-for="(_value, index) in (selectedChannel.allowFrom || [])" :key="index" class="flex gap-2">
              <Input v-model="selectedChannel.allowFrom[index]" type="text" placeholder="输入 userId / chatId / IP" />
              <Button variant="ghost" @click="removeAllowedIp(index)" class="text-error">
                <span class="material-symbols-outlined text-sm">delete</span>
              </Button>
            </div>
            <Button variant="ghost" @click="addAllowedIp" class="text-secondary">
              <span class="material-symbols-outlined text-sm">add</span>
              <span>添加</span>
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
import { computed, ref, onMounted } from 'vue';
import { useToast } from '@/composables/useToast';
import { channelsApi } from '@/api/channels';
import type { Channel } from '@/types';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Input from '@/components/common/Input.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

type EditableChannel = Omit<Channel, 'allowFrom'> & {
  allowFrom: string[];
} & Record<string, any>;

type FieldDef = {
  key: keyof EditableChannel;
  label: string;
  placeholder?: string;
  secret?: boolean;
  type?: 'text' | 'number' | 'select';
  options?: string[];
};

const toast = useToast();
const channels = ref<Channel[]>([]);
const loading = ref(true);
const selectedChannel = ref<EditableChannel | null>(null);
const saving = ref(false);

const channelFieldMap: Record<string, FieldDef[]> = {
  telegram: [
    { key: 'token', label: 'Bot Token', secret: true, placeholder: '输入 Telegram Bot Token' }
  ],
  discord: [
    { key: 'token', label: 'Bot Token', secret: true, placeholder: '输入 Discord Bot Token' }
  ],
  whatsapp: [
    { key: 'bridgeUrl', label: 'Bridge URL', placeholder: 'http://localhost:3001' }
  ],
  feishu: [
    { key: 'appId', label: 'App ID', secret: true, placeholder: '输入 App ID' },
    { key: 'appSecret', label: 'App Secret', secret: true, placeholder: '输入 App Secret' },
    { key: 'encryptKey', label: 'Encrypt Key', secret: true, placeholder: '输入 Encrypt Key' },
    { key: 'verificationToken', label: 'Verification Token', secret: true, placeholder: '输入 Verification Token' },
    { key: 'connectionMode', label: 'Connection Mode', type: 'select', options: ['websocket', 'webhook'] }
  ],
  dingtalk: [
    { key: 'clientId', label: 'Client ID', secret: true, placeholder: '输入 Client ID' },
    { key: 'clientSecret', label: 'Client Secret', secret: true, placeholder: '输入 Client Secret' },
    { key: 'webhook', label: 'Webhook', secret: true, placeholder: '输入 Webhook' },
    { key: 'connectionMode', label: 'Connection Mode', type: 'select', options: ['stream', 'webhook'] }
  ],
  qq: [
    { key: 'appId', label: 'App ID', secret: true, placeholder: '输入 App ID' },
    { key: 'appSecret', label: 'App Secret', secret: true, placeholder: '输入 App Secret' }
  ],
  maixcam: [
    { key: 'host', label: 'Host', placeholder: '0.0.0.0' },
    { key: 'port', label: 'Port', type: 'number', placeholder: '18790' }
  ]
};

const editableFields = computed(() => {
  if (!selectedChannel.value) return [];
  return channelFieldMap[selectedChannel.value.name] || [];
});

const loadChannels = async () => {
  loading.value = true;
  try {
    channels.value = await channelsApi.list();
  } catch (e) {
    toast.error('加载通道列表失败: ' + (e as Error).message);
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
    const detail = await channelsApi.get(name);
    selectedChannel.value = {
      ...detail,
      allowFrom: Array.isArray(detail.allowFrom) ? [...detail.allowFrom] : []
    };
  } catch (e) {
    toast.error('加载通道配置失败: ' + (e as Error).message);
  }
};

const closeModal = () => {
  selectedChannel.value = null;
};

const addAllowedIp = () => {
  if (!selectedChannel.value) return;
  if (!Array.isArray(selectedChannel.value.allowFrom)) {
    selectedChannel.value.allowFrom = [];
  }
  selectedChannel.value.allowFrom.push('');
};

const removeAllowedIp = (index: number) => {
  selectedChannel.value?.allowFrom?.splice(index, 1);
};

const toggleChannel = async (name: string, event: Event) => {
  const enabled = (event.target as HTMLInputElement).checked;
  try {
    await channelsApi.update(name, { enabled });
    toast.success(`通道已${enabled ? '启用' : '禁用'}`);
    await loadChannels();
  } catch (e) {
    toast.error('操作失败: ' + (e as Error).message);
    await loadChannels();
  }
};

const saveConfig = async () => {
  if (!selectedChannel.value) return;

  saving.value = true;
  try {
    const payload: Partial<Channel> = {
      enabled: selectedChannel.value.enabled,
      allowFrom: (selectedChannel.value.allowFrom || []).filter(Boolean),
      token: selectedChannel.value.token,
      bridgeUrl: selectedChannel.value.bridgeUrl,
      appId: selectedChannel.value.appId,
      appSecret: selectedChannel.value.appSecret,
      encryptKey: selectedChannel.value.encryptKey,
      verificationToken: selectedChannel.value.verificationToken,
      connectionMode: selectedChannel.value.connectionMode,
      clientId: selectedChannel.value.clientId,
      clientSecret: selectedChannel.value.clientSecret,
      webhook: selectedChannel.value.webhook,
      host: selectedChannel.value.host,
      port: selectedChannel.value.port != null
        ? Number(selectedChannel.value.port)
        : undefined
    };

    await channelsApi.update(selectedChannel.value.name, payload);
    toast.success('配置已保存');
    closeModal();
    await loadChannels();
  } catch (e) {
    toast.error('保存失败: ' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

onMounted(() => {
  loadChannels();
});
</script>
