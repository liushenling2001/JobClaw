<template>
  <button
    :class="[
      'px-4 py-2 rounded font-bold transition-all text-sm cursor-pointer',
      variant === 'primary' ? 'bg-primary text-on-primary-fixed' : '',
      variant === 'secondary' ? 'bg-secondary text-surface-container-lowest' : '',
      variant === 'ghost' ? 'bg-surface-container-high text-outline hover:bg-surface-container-highest' : '',
      disabled ? 'opacity-50 cursor-not-allowed disabled:shadow-none' : 'shadow-[0_0_15px_rgba(132,85,239,0.3)] hover:shadow-[0_0_25px_rgba(132,85,239,0.6)]'
    ]"
    :disabled="disabled"
    @click="$emit('click', $event)"
  >
    <slot />
  </button>
</template>

<script setup lang="ts">
defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost';
  disabled?: boolean;
}>();

defineEmits<{
  click: [event: MouseEvent];
}>();
</script>
