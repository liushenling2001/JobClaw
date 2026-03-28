<template>
  <div class="p-8">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-on-surface font-headline">定时任务</h1>
      <Button variant="primary" @click="showCreateModal = true" class="flex items-center gap-2">
        <span class="material-symbols-outlined text-sm">add</span>
        <span>新建任务</span>
      </Button>
    </div>

    <Card class="p-6">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="5" />
      </div>

      <div v-else-if="jobs.length === 0" class="flex flex-col items-center justify-center py-12 text-on-surface-variant">
        <span class="material-symbols-outlined text-6xl mb-4">schedule</span>
        <p>暂无定时任务</p>
      </div>

      <div v-else class="space-y-3">
        <div
          v-for="job in jobs"
          :key="job.id"
          class="flex items-center justify-between p-4 rounded-lg border border-outline-variant/20 hover:bg-surface-container-high transition-colors"
        >
          <div class="flex items-center gap-4 flex-1">
            <div :class="[
              'w-10 h-10 rounded-lg flex items-center justify-center border',
              job.enabled ? 'bg-secondary/20 border-secondary/30' : 'bg-surface-container-high border-outline-variant/30'
            ]">
              <span :class="[
                'material-symbols-outlined text-sm',
                job.enabled ? 'text-secondary' : 'text-outline-variant'
              ]">
                {{ job.enabled ? 'schedule' : 'pause_circle' }}
              </span>
            </div>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-bold text-on-surface font-headline">{{ job.name }}</div>
              <div class="text-xs text-on-surface-variant mt-1 truncate">{{ job.message }}</div>
              <div class="text-xs text-on-surface-variant mt-1 font-mono">
                <span v-if="job.schedule">{{ job.schedule }}</span>
                <span v-else-if="job.everySeconds">每 {{ job.everySeconds }} 秒</span>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-4">
            <div class="text-right">
              <div v-if="job.nextRun" class="text-xs text-on-surface-variant">
                下次执行：<span class="font-mono">{{ formatTime(job.nextRun) }}</span>
              </div>
              <span :class="[
                'text-xs px-2 py-0.5 rounded',
                job.enabled ? 'bg-green-500/20 text-green-400' : 'bg-outline-variant/20 text-outline-variant'
              ]">
                {{ job.enabled ? '运行中' : '已暂停' }}
              </span>
            </div>

            <div class="flex items-center gap-1">
              <Button variant="ghost" @click="toggleJob(job)" class="flex items-center gap-1">
                <span class="material-symbols-outlined text-sm">{{ job.enabled ? 'pause_circle' : 'play_circle' }}</span>
              </Button>
              <Button variant="ghost" @click="deleteJob(job)" class="flex items-center gap-1 text-error">
                <span class="material-symbols-outlined text-sm">delete</span>
              </Button>
            </div>
          </div>
        </div>
      </div>
    </Card>

    <!-- 创建任务模态框 -->
    <Modal v-if="showCreateModal" :model-value="showCreateModal" @update:model-value="showCreateModal = false">
      <div class="space-y-4">
        <h3 class="text-lg font-bold text-on-surface font-headline">创建定时任务</h3>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">任务名称</label>
          <Input v-model="newJob.name" type="text" placeholder="例如：每日备份" />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">执行消息</label>
          <Input v-model="newJob.message" type="text" placeholder="要执行的消息内容" />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">调度类型</label>
          <select v-model="newJob.scheduleType" class="w-full bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50">
            <option value="cron">Cron 表达式</option>
            <option value="every">每隔 N 秒</option>
          </select>
        </div>

        <div v-if="newJob.scheduleType === 'cron'">
          <label class="block text-sm text-on-surface-variant mb-2">Cron 表达式</label>
          <Input v-model="newJob.cron" type="text" placeholder="0 0 * * * ?" />
          <p class="text-xs text-on-surface-variant mt-1">格式：秒 分 时 日 月 周</p>
        </div>

        <div v-else>
          <label class="block text-sm text-on-surface-variant mb-2">间隔秒数</label>
          <input v-model.number="newJob.everySeconds" type="number" min="1" placeholder="60" class="w-full bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-2 focus:ring-secondary/50" />
        </div>

        <div class="flex justify-end gap-2 pt-4">
          <Button variant="ghost" @click="showCreateModal = false">取消</Button>
          <Button variant="primary" @click="createJob" :disabled="saving">
            {{ saving ? '创建中...' : '创建' }}
          </Button>
        </div>
      </div>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useToast } from '@/composables/useToast';
import { cronApi } from '@/api/cron';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Input from '@/components/common/Input.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface CronJob {
  id: string;
  name: string;
  message: string;
  enabled: boolean;
  schedule?: string;
  everySeconds?: number;
  nextRun?: number;
}

const toast = useToast();
const jobs = ref<CronJob[]>([]);
const loading = ref(true);
const showCreateModal = ref(false);
const saving = ref(false);

const newJob = reactive({
  name: '',
  message: '',
  scheduleType: 'cron',
  cron: '',
  everySeconds: 60
});

const loadJobs = async () => {
  loading.value = true;
  try {
    jobs.value = await cronApi.list();
  } catch (e) {
    toast.error('加载任务列表失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const toggleJob = async (job: CronJob) => {
  try {
    await cronApi.enable(job.id, !job.enabled);
    toast.success(job.enabled ? '任务已暂停' : '任务已启用');
    await loadJobs();
  } catch (e) {
    toast.error('操作失败：' + (e as Error).message);
  }
};

const deleteJob = async (job: CronJob) => {
  if (!confirm(`确定要删除任务 "${job.name}" 吗？`)) return;

  try {
    await cronApi.delete(job.id);
    toast.success('任务已删除');
    await loadJobs();
  } catch (e) {
    toast.error('删除失败：' + (e as Error).message);
  }
};

const createJob = async () => {
  if (!newJob.name || !newJob.message) {
    toast.error('请填写任务名称和消息');
    return;
  }

  const payload: any = {
    name: newJob.name,
    message: newJob.message
  };

  if (newJob.scheduleType === 'cron') {
    if (!newJob.cron) {
      toast.error('请填写 Cron 表达式');
      return;
    }
    payload.cron = newJob.cron;
  } else {
    if (!newJob.everySeconds || newJob.everySeconds < 1) {
      toast.error('请输入有效的间隔秒数');
      return;
    }
    payload.everySeconds = newJob.everySeconds;
  }

  saving.value = true;
  try {
    await cronApi.create(payload);
    toast.success('任务已创建');
    showCreateModal.value = false;
    // 重置表单
    newJob.name = '';
    newJob.message = '';
    newJob.cron = '';
    newJob.everySeconds = 60;
    await loadJobs();
  } catch (e) {
    toast.error('创建失败：' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

const formatTime = (timestamp: number): string => {
  return new Date(timestamp).toLocaleString('zh-CN');
};

onMounted(() => {
  loadJobs();
});
</script>
