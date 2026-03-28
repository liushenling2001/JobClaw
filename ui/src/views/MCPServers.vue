<template>
  <div class="p-8">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-on-surface font-headline">MCP 服务器</h1>
      <div class="flex items-center gap-4">
        <label class="flex items-center gap-2 text-sm text-on-surface">
          <input type="checkbox" v-model="mcpEnabled" @change="toggleMcpGlobal" class="w-4 h-4 rounded border-outline-variant bg-surface-container-high text-secondary focus:ring-secondary" />
          <span>MCP 全局启用</span>
        </label>
        <Button variant="primary" @click="showAddModal = true" class="flex items-center gap-2">
          <span class="material-symbols-outlined text-sm">add</span>
          <span>添加服务器</span>
        </Button>
      </div>
    </div>

    <Card class="p-6">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="3" />
      </div>

      <div v-else-if="servers.length === 0" class="flex flex-col items-center justify-center py-12 text-on-surface-variant">
        <span class="material-symbols-outlined text-6xl mb-4">dns</span>
        <p>暂无 MCP 服务器</p>
      </div>

      <div v-else class="space-y-4">
        <div
          v-for="server in servers"
          :key="server.name"
          class="p-4 rounded-lg border border-outline-variant/20 hover:bg-surface-container-high transition-colors"
        >
          <div class="flex items-start justify-between mb-3">
            <div class="flex items-center gap-3">
              <div :class="[
                'w-10 h-10 rounded-lg flex items-center justify-center border',
                server.enabled ? 'bg-primary/20 border-primary/30' : 'bg-surface-container-high border-outline-variant/30'
              ]">
                <span :class="[
                  'material-symbols-outlined text-sm',
                  server.enabled ? 'text-primary' : 'text-outline-variant'
                ]">
                  {{ server.type === 'sse' ? 'cloud' : 'terminal' }}
                </span>
              </div>
              <div>
                <h3 class="text-sm font-bold text-on-surface font-headline">{{ server.name }}</h3>
                <p class="text-xs text-on-surface-variant">{{ server.description }}</p>
              </div>
            </div>

            <div class="flex items-center gap-2">
              <span :class="[
                'text-xs px-2 py-0.5 rounded',
                server.enabled ? 'bg-green-500/20 text-green-400' : 'bg-outline-variant/20 text-outline-variant'
              ]">
                {{ server.enabled ? '已启用' : '已禁用' }}
              </span>
            </div>
          </div>

          <div class="flex items-center justify-between">
            <div class="text-xs text-on-surface-variant font-mono">
              <span v-if="server.type === 'sse'">SSE: </span>
              <span v-else>STDIO: </span>
              <span>{{ server.endpoint || server.command }}</span>
            </div>

            <div class="flex items-center gap-1">
              <Button variant="ghost" @click="testServer(server)" :disabled="testing" class="flex items-center gap-1">
                <span class="material-symbols-outlined text-sm">{{ testing ? 'sync' : 'network_ping' }}</span>
                <span>{{ testing ? '测试中...' : '测试' }}</span>
              </Button>
              <Button variant="ghost" @click="editServer(server)" class="flex items-center gap-1">
                <span class="material-symbols-outlined text-sm">edit</span>
              </Button>
              <Button variant="ghost" @click="toggleServer(server)" class="flex items-center gap-1">
                <span class="material-symbols-outlined text-sm">{{ server.enabled ? 'pause' : 'play_arrow' }}</span>
              </Button>
              <Button variant="ghost" @click="deleteServer(server)" class="flex items-center gap-1 text-error">
                <span class="material-symbols-outlined text-sm">delete</span>
              </Button>
            </div>
          </div>
        </div>
      </div>
    </Card>

    <!-- 添加/编辑服务器模态框 -->
    <Modal
      v-if="showAddModal || showEditModal"
      :model-value="showAddModal || showEditModal"
      @update:model-value="showAddModal = false; showEditModal = false"
    >
      <div class="space-y-4">
        <h3 class="text-lg font-bold text-on-surface font-headline">
          {{ showEditModal ? '编辑' : '添加' }} MCP 服务器
        </h3>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">服务器名称</label>
          <Input v-model="formData.name" type="text" :readonly="showEditModal" placeholder="my-server" />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">描述</label>
          <Input v-model="formData.description" type="text" placeholder="服务器描述" />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">类型</label>
          <select v-model="formData.type" class="w-full bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50">
            <option value="sse">SSE (Server-Sent Events)</option>
            <option value="stdio">STDIO (标准输入输出)</option>
          </select>
        </div>

        <div v-if="formData.type === 'sse'">
          <label class="block text-sm text-on-surface-variant mb-2">SSE Endpoint</label>
          <Input v-model="formData.endpoint" type="text" placeholder="https://example.com/sse" />
        </div>

        <div v-else>
          <label class="block text-sm text-on-surface-variant mb-2">命令</label>
          <Input v-model="formData.command" type="text" placeholder="npx -y @example/server" />
        </div>

        <div v-if="formData.type === 'stdio'" class="space-y-2">
          <label class="block text-sm text-on-surface-variant">参数 (每行一个)</label>
          <textarea
            v-model="formData.argsText"
            rows="3"
            class="w-full bg-surface-container-lowest border-none text-sm px-4 py-3 rounded text-on-surface font-mono resize-none focus:outline-none focus:ring-2 focus:ring-secondary/50"
            placeholder="--arg1&#10;--arg2"
          ></textarea>
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">API Key (可选)</label>
          <Input v-model="formData.apiKey" type="password" placeholder="sk-..." />
        </div>

        <div class="flex items-center gap-2">
          <input type="checkbox" v-model="formData.enabled" class="w-4 h-4 rounded border-outline-variant bg-surface-container-high text-secondary focus:ring-secondary" />
          <label class="text-sm text-on-surface">启用此服务器</label>
        </div>

        <div class="flex justify-end gap-2 pt-2">
          <Button variant="ghost" @click="showAddModal = false; showEditModal = false">取消</Button>
          <Button variant="primary" @click="saveServer" :disabled="saving">
            {{ saving ? '保存中...' : '保存' }}
          </Button>
        </div>
      </div>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useToast } from '@/composables/useToast';
import { mcpApi } from '@/api/mcp';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Input from '@/components/common/Input.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface MCPServer {
  name: string;
  type: 'sse' | 'stdio';
  description: string;
  endpoint?: string;
  command?: string;
  args?: string[];
  apiKey?: string;
  enabled: boolean;
  timeout: number;
}

const toast = useToast();
const servers = ref<MCPServer[]>([]);
const loading = ref(true);
const testing = ref(false);
const saving = ref(false);
const mcpEnabled = ref(true);

const showAddModal = ref(false);
const showEditModal = ref(false);

const formData = reactive<MCPServer & { argsText: string }>({
  name: '',
  type: 'sse',
  description: '',
  endpoint: '',
  command: '',
  args: [],
  apiKey: '',
  enabled: true,
  timeout: 30000,
  argsText: ''
});

const resetForm = () => {
  formData.name = '';
  formData.type = 'sse';
  formData.description = '';
  formData.endpoint = '';
  formData.command = '';
  formData.args = [];
  formData.apiKey = '';
  formData.enabled = true;
  formData.timeout = 30000;
  formData.argsText = '';
};

const loadServers = async () => {
  loading.value = true;
  try {
    const data = await mcpApi.getConfig();
    mcpEnabled.value = data.enabled;
    servers.value = data.servers || [];
  } catch (e) {
    toast.error('加载 MCP 配置失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const toggleMcpGlobal = async () => {
  try {
    await mcpApi.updateEnabled(mcpEnabled.value);
    toast.success(mcpEnabled.value ? 'MCP 已启用' : 'MCP 已禁用');
  } catch (e) {
    toast.error('操作失败：' + (e as Error).message);
    mcpEnabled.value = !mcpEnabled.value;
  }
};

const testServer = async (server: MCPServer) => {
  testing.value = true;
  try {
    const result = await mcpApi.test(server.name);
    toast.success(`连接成功！${result.connected ? '已连接' : ''}`);
  } catch (e) {
    toast.error('测试失败：' + (e as Error).message);
  } finally {
    testing.value = false;
  }
};

const toggleServer = async (server: MCPServer) => {
  try {
    await mcpApi.updateServer(server.name, { ...server, enabled: !server.enabled });
    toast.success(server.enabled ? '服务器已禁用' : '服务器已启用');
    await loadServers();
  } catch (e) {
    toast.error('操作失败：' + (e as Error).message);
  }
};

const editServer = (server: MCPServer) => {
  showEditModal.value = true;
  formData.name = server.name;
  formData.type = server.type;
  formData.description = server.description;
  formData.endpoint = server.endpoint || '';
  formData.command = server.command || '';
  formData.args = server.args || [];
  formData.argsText = server.args?.join('\n') || '';
  formData.apiKey = server.apiKey || '';
  formData.enabled = server.enabled;
  formData.timeout = server.timeout;
};

const saveServer = async () => {
  if (!formData.name) {
    toast.error('请输入服务器名称');
    return;
  }

  saving.value = true;
  try {
    const args = formData.argsText.split('\n').filter(a => a.trim());
    const data = {
      ...formData,
      args
    };

    if (showEditModal.value) {
      await mcpApi.updateServer(formData.name, data);
      toast.success('服务器配置已更新');
    } else {
      await mcpApi.add(data);
      toast.success('服务器已添加');
    }

    showAddModal.value = false;
    showEditModal.value = false;
    resetForm();
    await loadServers();
  } catch (e) {
    toast.error('保存失败：' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

const deleteServer = async (server: MCPServer) => {
  if (!confirm(`确定要删除服务器 "${server.name}" 吗？`)) return;

  try {
    await mcpApi.delete(server.name);
    toast.success('服务器已删除');
    await loadServers();
  } catch (e) {
    toast.error('删除失败：' + (e as Error).message);
  }
};

onMounted(() => {
  loadServers();
});
</script>
