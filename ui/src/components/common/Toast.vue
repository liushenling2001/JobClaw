<template>
  <div class="fixed top-20 right-6 w-72 space-y-3 z-50 pointer-events-none">
    <TransitionGroup name="slide">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        :class="[
          'glass-panel p-4 border-l-4 rounded-r-lg shadow-xl animate-bounce-subtle',
          toast.type === 'error' ? 'border-error/80' : 'border-secondary/80'
        ]"
      >
        <div class="flex gap-3">
          <span :class="toast.type === 'error' ? 'text-error' : 'text-secondary'" class="material-symbols-outlined">
            {{ toast.type === 'error' ? 'priority_high' : 'verified_user' }}
          </span>
          <div>
            <h4 class="text-xs font-bold text-on-surface">{{ toast.message }}</h4>
          </div>
        </div>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia';
import { useToastStore } from '@/stores/toast';

const store = useToastStore();
const { toasts } = storeToRefs(store);
</script>

<style scoped>
.slide-enter-active,
.slide-leave-active {
  transition: all 0.3s ease;
}
.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateX(30px);
}

.animate-bounce-subtle {
  animation: bounce-subtle 2s ease-in-out infinite;
}

@keyframes bounce-subtle {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-4px); }
}
</style>
