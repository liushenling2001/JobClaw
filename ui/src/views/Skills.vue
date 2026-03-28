<template>
  <div class="p-8">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-on-surface font-headline">技能管理</h1>
      <Button variant="primary" @click="showCreateModal = true" class="flex items-center gap-2">
        <span class="material-symbols-outlined text-sm">add</span>
        <span>新建技能</span>
      </Button>
    </div>

    <Card class="p-6">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Skeleton type="table" :rows="5" />
      </div>

      <div v-else-if="skills.length === 0" class="flex flex-col items-center justify-center py-12 text-on-surface-variant">
        <span class="material-symbols-outlined text-6xl mb-4">build</span>
        <p>暂无技能</p>
      </div>

      <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div
          v-for="skill in skills"
          :key="skill.name"
          class="p-4 rounded-lg border border-outline-variant/20 hover:bg-surface-container-high transition-colors"
        >
          <div class="flex items-start justify-between mb-2">
            <h3 class="text-sm font-bold text-on-surface font-headline">{{ skill.name }}</h3>
            <div class="flex items-center gap-1">
              <Button variant="ghost" @click="viewSkill(skill.name)" class="flex items-center gap-1">
                <span class="material-symbols-outlined text-sm">visibility</span>
              </Button>
              <Button variant="ghost" @click="editSkill(skill.name)" class="flex items-center gap-1">
                <span class="material-symbols-outlined text-sm">edit</span>
              </Button>
              <Button variant="ghost" @click="deleteSkill(skill)" class="flex items-center gap-1 text-error">
                <span class="material-symbols-outlined text-sm">delete</span>
              </Button>
            </div>
          </div>
          <p class="text-xs text-on-surface-variant line-clamp-2">{{ skill.description }}</p>
          <div class="text-xs text-on-surface-variant mt-2 font-mono truncate">{{ skill.path || skill.source }}</div>
        </div>
      </div>
    </Card>

    <!-- 查看/编辑技能模态框 -->
    <Modal
      v-if="showEditModal"
      :model-value="showEditModal"
      @update:model-value="showEditModal = false"
    >
      <div class="space-y-4">
        <h3 class="text-lg font-bold text-on-surface font-headline">
          {{ editingMode === 'view' ? '查看技能' : '编辑技能' }} - {{ selectedSkill.name }}
        </h3>

        <div v-if="loadingSkill" class="py-8 flex items-center justify-center">
          <Skeleton type="text" :lines="10" />
        </div>

        <div v-else class="space-y-4">
          <div>
            <label class="block text-sm text-on-surface-variant mb-2">技能内容</label>
            <textarea
              v-model="selectedSkill.content"
              :readonly="editingMode === 'view'"
              rows="15"
              class="w-full bg-surface-container-lowest border-none text-sm px-4 py-3 rounded text-on-surface font-mono resize-none focus:outline-none focus:ring-2 focus:ring-secondary/50 disabled:opacity-50"
            ></textarea>
          </div>

          <div v-if="editingMode === 'edit'" class="flex justify-end gap-2">
            <Button variant="ghost" @click="showEditModal = false">取消</Button>
            <Button variant="primary" @click="saveSkill" :disabled="saving">
              {{ saving ? '保存中...' : '保存' }}
            </Button>
          </div>
        </div>
      </div>
    </Modal>

    <!-- 创建技能模态框 -->
    <Modal v-if="showCreateModal" :model-value="showCreateModal" @update:model-value="showCreateModal = false">
      <div class="space-y-4">
        <h3 class="text-lg font-bold text-on-surface font-headline">创建技能</h3>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">技能名称</label>
          <Input v-model="newSkill.name" type="text" placeholder="例如：my-skill" />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">技能描述</label>
          <Input v-model="newSkill.description" type="text" placeholder="技能描述..." />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-2">技能内容</label>
          <textarea
            v-model="newSkill.content"
            rows="10"
            class="w-full bg-surface-container-lowest border-none text-sm px-4 py-3 rounded text-on-surface font-mono resize-none focus:outline-none focus:ring-2 focus:ring-secondary/50"
            placeholder="# Skill: my-skill&#10;&#10;## Description&#10;描述你的技能..."
          ></textarea>
        </div>

        <div class="flex justify-end gap-2 pt-2">
          <Button variant="ghost" @click="showCreateModal = false">取消</Button>
          <Button variant="primary" @click="createSkill" :disabled="saving">
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
import { skillsApi } from '@/api/skills';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Input from '@/components/common/Input.vue';
import Modal from '@/components/common/Modal.vue';
import Skeleton from '@/components/common/Skeleton.vue';

interface Skill {
  name: string;
  description: string;
  source: string;
  path?: string;
  content?: string;
}

const toast = useToast();
const skills = ref<Skill[]>([]);
const loading = ref(true);
const loadingSkill = ref(false);
const showCreateModal = ref(false);
const showEditModal = ref(false);
const editingMode = ref<'view' | 'edit'>('view');
const saving = ref(false);

const selectedSkill = reactive<Skill>({ name: '', description: '', source: '' });

const newSkill = reactive({
  name: '',
  description: '',
  content: ''
});

const loadSkills = async () => {
  loading.value = true;
  try {
    skills.value = await skillsApi.list();
  } catch (e) {
    toast.error('加载技能列表失败：' + (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const viewSkill = async (name: string) => {
  loadingSkill.value = true;
  editingMode.value = 'view';
  selectedSkill.name = name;
  selectedSkill.description = '';
  selectedSkill.content = '';

  try {
    const data = await skillsApi.get(name);
    selectedSkill.content = data.content;
    showEditModal.value = true;
  } catch (e) {
    toast.error('加载技能内容失败：' + (e as Error).message);
  } finally {
    loadingSkill.value = false;
  }
};

const editSkill = async (name: string) => {
  loadingSkill.value = true;
  editingMode.value = 'edit';
  selectedSkill.name = name;
  selectedSkill.description = '';
  selectedSkill.content = '';

  try {
    const data = await skillsApi.get(name);
    selectedSkill.content = data.content;
    showEditModal.value = true;
  } catch (e) {
    toast.error('加载技能内容失败：' + (e as Error).message);
  } finally {
    loadingSkill.value = false;
  }
};

const saveSkill = async () => {
  if (!selectedSkill.name || !selectedSkill.content) {
    toast.error('技能内容不能为空');
    return;
  }

  saving.value = true;
  try {
    await skillsApi.save(selectedSkill.name, selectedSkill.content);
    toast.success('技能已保存');
    showEditModal.value = false;
    await loadSkills();
  } catch (e) {
    toast.error('保存失败：' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

const createSkill = async () => {
  if (!newSkill.name) {
    toast.error('请输入技能名称');
    return;
  }

  saving.value = true;
  try {
    const content = newSkill.content || `# Skill: ${newSkill.name}\n\n${newSkill.description ? `## Description\n${newSkill.description}` : ''}`;
    await skillsApi.save(newSkill.name, content);
    toast.success('技能已创建');
    showCreateModal.value = false;
    newSkill.name = '';
    newSkill.description = '';
    newSkill.content = '';
    await loadSkills();
  } catch (e) {
    toast.error('创建失败：' + (e as Error).message);
  } finally {
    saving.value = false;
  }
};

const deleteSkill = async (skill: Skill) => {
  if (!confirm(`确定要删除技能 "${skill.name}" 吗？此操作不可恢复。`)) return;

  try {
    await skillsApi.delete(skill.name);
    toast.success('技能已删除');
    await loadSkills();
  } catch (e) {
    toast.error('删除失败：' + (e as Error).message);
  }
};

onMounted(() => {
  loadSkills();
});
</script>
