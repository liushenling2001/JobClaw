<template>
  <div class="skeleton" :class="[`skeleton--${type}`]">
    <template v-if="type === 'text'">
      <div class="skeleton-line" v-for="i in lines" :key="i" :class="{ short: i === 1 }"></div>
    </template>
    <template v-else-if="type === 'card'">
      <div class="skeleton-card-image"></div>
      <div class="skeleton-card-content">
        <div class="skeleton-line short"></div>
        <div class="skeleton-line"></div>
      </div>
    </template>
    <template v-else-if="type === 'table'">
      <div class="skeleton-table-row" v-for="i in rows" :key="i"></div>
    </template>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  type: 'text' | 'card' | 'table';
  lines?: number;
  rows?: number;
}>();
</script>

<style scoped>
.skeleton {
  background: linear-gradient(90deg, #1a1f2e 25%, #11192e 50%, #1a1f2e 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.skeleton-line {
  height: 1rem;
  border-radius: 0.25rem;
  margin-bottom: 0.5rem;
  background: rgba(255, 255, 255, 0.1);
}

.skeleton-line.short {
  width: 50%;
}

.skeleton-card-image {
  height: 8rem;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 0.5rem;
  margin-bottom: 0.5rem;
}

.skeleton-table-row {
  height: 2rem;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 0.25rem;
  margin-bottom: 0.5rem;
}
</style>
