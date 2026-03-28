<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">系统设置</h1>

    <div class="space-y-6">
      <!-- 认证设置 -->
      <Card class="p-6">
        <h2 class="text-lg font-bold text-on-surface mb-4 flex items-center gap-2">
          <span class="material-symbols-outlined text-primary">lock</span>
          <span>认证设置</span>
        </h2>

        <div v-if="authLoading" class="space-y-4">
          <Skeleton type="text" :lines="3" />
        </div>

        <div v-else class="space-y-4">
          <div class="flex items-center justify-between p-3 rounded-lg bg-surface-container-high">
            <div>
              <div class="text-sm font-bold text-on-surface">认证状态</div>
              <div class="text-xs text-on-surface-variant mt-1">
                {{ authEnabled ? '已启用认证' : '未启用认证' }}
              </div>
            </div>
            <span :class="[
              'text-xs px-2 py-0.5 rounded',
              authEnabled ? 'bg-green-500/20 text-green-400' : 'bg-outline-variant/20 text-outline-variant'
            ]">
              {{ authenticated ? '已登录' : '未登录' }}
            </span>
          </div>

          <div v-if="authEnabled && !authenticated" class="space-y-4">
            <div>
              <label class="block text-sm text-on-surface-variant mb-2">用户名</label>
              <Input v-model="loginForm.username" type="text" placeholder="请输入用户名" />
            </div>
            <div>
              <label class="block text-sm text-on-surface-variant mb-2">密码</label>
              <Input v-model="loginForm.password" type="password" placeholder="请输入密码" />
            </div>
            <Button variant="primary" @click="handleLogin" :disabled="loggingIn" class="w-full">
              {{ loggingIn ? '登录中...' : '登录' }}
            </Button>
          </div>

          <div v-if="authenticated" class="flex justify-end">
            <Button variant="secondary" @click="handleLogout" :disabled="loggingOut">
              {{ loggingOut ? '退出中...' : '退出登录' }}
            </Button>
          </div>
        </div>
      </Card>

      <!-- 系统信息 -->
      <Card class="p-6">
        <h2 class="text-lg font-bold text-on-surface mb-4 flex items-center gap-2">
          <span class="material-symbols-outlined text-secondary">info</span>
          <span>系统信息</span>
        </h2>

        <div class="space-y-3">
          <div class="flex justify-between py-2 border-b border-outline-variant/10">
            <span class="text-sm text-on-surface-variant">工作空间</span>
            <span class="text-sm text-on-surface font-mono">{{ workspacePath }}</span>
          </div>
          <div class="flex justify-between py-2 border-b border-outline-variant/10">
            <span class="text-sm text-on-surface-variant">当前模型</span>
            <span class="text-sm text-on-surface font-mono">{{ currentModel }}</span>
          </div>
          <div class="flex justify-between py-2 border-b border-outline-variant/10">
            <span class="text-sm text-on-surface-variant">活跃会话</span>
            <span class="text-sm text-on-surface font-mono">{{ sessionCount }}</span>
          </div>
          <div class="flex justify-between py-2">
            <span class="text-sm text-on-surface-variant">系统状态</span>
            <span class="text-sm text-green-400 flex items-center gap-1">
              <span class="w-2 h-2 rounded-full bg-green-400 animate-pulse"></span>
              运行中
            </span>
          </div>
        </div>
      </Card>

      <!-- 关于 -->
      <Card class="p-6">
        <h2 class="text-lg font-bold text-on-surface mb-4 flex items-center gap-2">
          <span class="material-symbols-outlined text-tertiary">code</span>
          <span>关于 JobClaw</span>
        </h2>

        <div class="space-y-2 text-sm text-on-surface-variant">
          <p>JobClaw 是一个强大的 AI Agent 控制台，支持多种 LLM 提供商、通道集成和技能扩展。</p>
          <p class="font-mono">Version: 1.0.0</p>
        </div>
      </Card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useToast } from '@/composables/useToast';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';
import Input from '@/components/common/Input.vue';
import Skeleton from '@/components/common/Skeleton.vue';

const router = useRouter();
const authStore = useAuthStore();
const toast = useToast();

const authLoading = ref(true);
const authenticated = ref(false);
const authEnabled = ref(true);
const workspacePath = ref('-');
const currentModel = ref('-');
const sessionCount = ref(0);

const loggingIn = ref(false);
const loggingOut = ref(false);
const loginForm = ref({ username: '', password: '' });

const loadStatus = async () => {
  authLoading.value = true;
  try {
    // 检查认证状态
    const authRes = await fetch('/api/auth/check');
    const authData = await authRes.json();
    authenticated.value = authData.authenticated;
    authEnabled.value = authData.authEnabled !== false;

    // 加载系统状态
    const statusRes = await fetch('/api/status');
    const statusData = await statusRes.json();
    workspacePath.value = statusData.workspace || '-';
    currentModel.value = statusData.model || '-';
    sessionCount.value = statusData.sessions || 0;
  } catch (e) {
    console.error('Failed to load status:', e);
  } finally {
    authLoading.value = false;
  }
};

const handleLogin = async () => {
  if (!loginForm.value.username || !loginForm.value.password) {
    toast.error('请输入用户名和密码');
    return;
  }

  loggingIn.value = true;
  try {
    const response = await authStore.login(loginForm.value.username, loginForm.value.password);
    if (response.success) {
      toast.success('登录成功');
      authenticated.value = true;
      loginForm.value = { username: '', password: '' };
    } else {
      toast.error(response.error || '登录失败');
    }
  } catch (e) {
    toast.error('网络错误，请稍后重试');
  } finally {
    loggingIn.value = false;
  }
};

const handleLogout = () => {
  loggingOut.value = true;
  authStore.logout();
  authenticated.value = false;
  toast.success('已退出登录');
  loggingOut.value = false;
};

onMounted(() => {
  loadStatus();
});
</script>
