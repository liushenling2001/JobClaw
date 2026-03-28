<template>
  <div class="min-h-screen flex items-center justify-center bg-background">
    <Card class="w-96 p-8">
      <h1 class="text-2xl font-bold text-on-surface font-headline mb-6 text-center">JobClaw 登录</h1>

      <form @submit.prevent="handleSubmit" class="space-y-4">
        <div>
          <label class="block text-sm text-on-surface-variant mb-1">用户名</label>
          <Input v-model="username" type="text" placeholder="请输入用户名" />
        </div>

        <div>
          <label class="block text-sm text-on-surface-variant mb-1">密码</label>
          <Input v-model="password" type="password" placeholder="请输入密码" />
        </div>

        <div v-if="error" class="text-error text-sm">{{ error }}</div>

        <Button type="submit" variant="primary" class="w-full" :disabled="isLoading">
          {{ isLoading ? '登录中...' : '登录' }}
        </Button>
      </form>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useToast } from '@/composables/useToast';
import Card from '@/components/common/Card.vue';
import Input from '@/components/common/Input.vue';
import Button from '@/components/common/Button.vue';

const router = useRouter();
const authStore = useAuthStore();
const toast = useToast();

const username = ref('');
const password = ref('');
const error = ref('');
const isLoading = ref(false);

const handleSubmit = async () => {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码';
    return;
  }

  isLoading.value = true;
  error.value = '';

  try {
    const response = await authStore.login(username.value, password.value);
    if (response.success) {
      toast.success('登录成功');
      router.push('/dashboard');
    } else {
      error.value = response.error || '登录失败';
      toast.error(error.value);
    }
  } catch (e) {
    error.value = '网络错误，请稍后重试';
    toast.error(error.value);
  } finally {
    isLoading.value = false;
  }
};
</script>
