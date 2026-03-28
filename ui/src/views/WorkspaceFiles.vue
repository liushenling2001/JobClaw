<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">工作空间文件</h1>

    <Card class="p-6">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="8" />
      </div>

      <div v-else-if="files.length === 0" class="flex flex-col items-center justify-center py-12 text-on-surface-variant">
        <span class="material-symbols-outlined text-6xl mb-4">folder_open</span>
        <p>工作空间为空</p>
      </div>

      <div v-else class="space-y-2">
        <div class="flex items-center gap-4 px-4 py-2 text-xs text-on-surface-variant border-b border-outline-variant/20">
          <div class="flex-1">文件名</div>
          <div class="w-32">大小</div>
          <div class="w-48">修改时间</div>
          <div class="w-24 text-right">操作</div>
        </div>

        <div
          v-for="file in files"
          :key="file.name"
          class="flex items-center gap-4 px-4 py-3 rounded-lg hover:bg-surface-container-high transition-colors cursor-pointer"
          @click="openFile(file)"
        >
          <div class="flex items-center gap-3 flex-1 min-w-0">
            <span class="material-symbols-outlined text-lg text-outline-variant">description</span>
            <span class="text-sm text-on-surface font-mono truncate">{{ file.name }}</span>
          </div>
          <div class="w-32 text-sm text-on-surface-variant font-mono">{{ formatSize(file.size) }}</div>
          <div class="w-48 text-sm text-on-surface-variant font-mono">{{ formatTime(file.lastModified) }}</div>
          <div class="w-24 flex justify-end gap-1" @click.stop>
            <Button variant="ghost" @click="openFile(file)" class="flex items-center gap-1">
              <span class="material-symbols-outlined text-sm">edit</span>
              <span>编辑</span>
            </Button>
          </div>
        </div>
      </div>
    </Card>

    <!-- 文件编辑模态框 -->
    <Modal
      v-if="showEditModal"
      :model-value="showEditModal"
      @update:model-value="showEditModal = false"
      class="max-w-4xl"
    >
      <div class="space-y-4">
        <div class="flex items-center justify-between">
          <h3 class="text-lg font-bold text-on-surface font-headline">编辑文件</h3>
          <span class="text-sm text-on-surface-variant font-mono">{{ selectedFile?.name || '' }}</span>
        </div>

        <div v-if="loadingFile" class="py-8 flex items-center justify-center">
          <Skeleton type="text" :lines="15" />
        </div>

        <div v-else class="space-y-4">
          <textarea
            v-model="fileContent"
            rows="20"
            class="w-full bg-surface-container-lowest border-none text-sm px-4 py-3 rounded text-on-surface font-mono resize-none focus:outline-none focus:ring-2 focus:ring-secondary/50"
          ></textarea>

          <div class="flex justify-end gap-2">
            <Button variant="ghost" @click="showEditModal = false">取消</Button>
            <Button variant="primary" @click="saveFile" :disabled="saving">
              {{ saving ? '保存中...' : '保存' }}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useToast } from '@/composables/useToast';
import { filesApi } from '@/api/files';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface FileInfo {
  name: string;
  exists: boolean;
  size: number;
  lastModified: number;
  content?: string;
}

const toast = useToast();
const files = ref<FileInfo[]>([]);
const loading = ref(true);
const loadingFile = ref(false);
const saving = ref(false);
const showEditModal = ref(false);
const selectedFile = ref<FileInfo | null>(null);
const fileContent = ref('');

const loadFiles = async () => {
  loading.value = true;
  try {
    files.value = await filesApi.list();
  } catch (e) {
    toast.error('加载文件列表失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const openFile = async (file: FileInfo) => {
  loadingFile.value = true;
  selectedFile.value = file;

  try {
    const data = await filesApi.read(file.name);
    fileContent.value = data.content || '';
    showEditModal.value = true;
  } catch (e) {
    toast.error('读取文件失败：' + (e as Error).message);
    showEditModal.value = false;
  } finally {
    loadingFile.value = false;
  }
};

const saveFile = async () => {
  if (!selectedFile.value) return;

  saving.value = true;
  try {
    await filesApi.save(selectedFile.value.name, fileContent.value);
    toast.success('文件已保存');
    showEditModal.value = false;
    await loadFiles();
  } catch (e) {
    toast.error('保存失败：' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

const formatSize = (bytes: number): string => {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

const formatTime = (ms: number): string => {
  return new Date(ms).toLocaleString('zh-CN');
};

onMounted(() => {
  loadFiles();
});
</script>

<style scoped>
.max-w-4xl {
  max-width: 56rem;
}
</style>
