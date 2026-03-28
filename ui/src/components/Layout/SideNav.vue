<template>
  <aside class="fixed left-0 top-16 h-[calc(100vh-64px)] w-64 bg-[#0c1326]/90 backdrop-blur-lg border-r border-[#41475b]/15 flex flex-col py-8 gap-4 z-40">
    <div class="px-6 mb-4">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-lg bg-primary/20 flex items-center justify-center border border-primary/30">
          <span class="material-symbols-outlined text-primary">hub</span>
        </div>
        <div>
          <h3 class="text-sm font-bold text-on-surface font-headline leading-tight">JobClaw System</h3>
          <p class="text-[10px] font-mono tracking-widest uppercase text-secondary">● Online</p>
        </div>
      </div>
    </div>
    <nav class="flex-1 space-y-1">
      <router-link to="/dashboard" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] hover:bg-[#171f36]/60 transition-all text-sm font-medium">
        <span class="material-symbols-outlined">dashboard</span>
        仪表盘
      </router-link>
      <router-link to="/chat" class="flex items-center gap-4 px-6 py-3 text-[#ba9eff] border-l-2 border-[#53ddfc] bg-[#171f36]/40 transition-all text-sm font-medium">
        <span class="material-symbols-outlined">forum</span>
        聊天
      </router-link>
      <router-link to="/sessions" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] hover:bg-[#171f36]/60 transition-all text-sm font-medium">
        <span class="material-symbols-outlined">chat</span>
        会话
      </router-link>
      <router-link to="/channels" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] hover:bg-[#171f36]/60 transition-all text-sm font-medium">
        <span class="material-symbols-outlined">hub</span>
        通道
      </router-link>
      <router-link to="/providers" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] hover:bg-[#171f36]/60 transition-all text-sm font-medium">
        <span class="material-symbols-outlined">dns</span>
        Provider
      </router-link>
      <router-link to="/settings" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] hover:bg-[#171f36]/60 transition-all text-sm font-medium">
        <span class="material-symbols-outlined">settings</span>
        设置
      </router-link>
    </nav>
    <div class="px-6 mt-auto space-y-4">
      <button class="w-full py-2.5 px-4 bg-gradient-to-r from-primary-dim to-primary text-on-primary-fixed font-bold rounded-lg shadow-[0_0_15px_rgba(132,85,239,0.3)] hover:brightness-110 transition-all font-headline text-sm cursor-pointer">
        Deploy Agent
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
// 侧边导航栏组件
</script>
