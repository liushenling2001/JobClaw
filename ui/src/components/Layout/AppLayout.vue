<template>
  <div class="min-h-screen bg-background">
    <TopNav />
    <div class="flex">
      <SideNav />
      <main class="ml-64 mt-16 h-[calc(100vh-64px)] flex-1 overflow-auto">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import TopNav from './TopNav.vue';
import SideNav from './SideNav.vue';
</script>
