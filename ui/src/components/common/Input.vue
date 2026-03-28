<template>
  <input
    :value="modelValue"
    :type="type"
    :placeholder="placeholder"
    :disabled="disabled"
    class="bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-1 focus:ring-secondary/50 placeholder:text-outline-variant disabled:opacity-50 w-full"
    @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  />
</template>

<script setup lang="ts">
defineProps<{
  modelValue?: string;
  type?: string;
  placeholder?: string;
  disabled?: boolean;
}>();

defineEmits<{
  'update:modelValue': [value: string];
}>();
</script>
