<template>
  <Teleport to="body">
    <div v-if="modelValue" class="fixed inset-0 z-50 flex items-center justify-center">
      <div class="absolute inset-0 bg-black/50" @click="$emit('update:modelValue', false)"></div>
      <div class="relative glass-panel p-6 rounded-lg border border-outline-variant/20 max-w-md w-full mx-4 z-10">
        <slot />
        <button class="absolute top-2 right-2 material-symbols-outlined text-on-surface/60 hover:text-on-surface cursor-pointer" @click="$emit('update:modelValue', false)">close</button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: boolean;
}>();

defineEmits<{
  'update:modelValue': [value: boolean];
}>();
</script>
