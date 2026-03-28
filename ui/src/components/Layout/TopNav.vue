<template>
  <nav class="fixed top-0 w-full z-50 bg-[#070d1f]/80 backdrop-blur-xl border-b border-[#41475b]/20 shadow-[0_8px_32px_0_rgba(132,85,239,0.1)] flex justify-between items-center px-6 h-16">
    <div class="flex items-center gap-8">
      <span class="text-2xl font-bold bg-gradient-to-r from-[#8455ef] to-[#ba9eff] bg-clip-text text-transparent font-headline tracking-tight">JobClaw</span>
      <div class="hidden md:flex gap-6 font-headline tracking-tight">
        <router-link to="/dashboard" class="text-[#dfe4fe]/60 hover:text-[#53ddfc] transition-colors">仪表盘</router-link>
        <router-link to="/chat" class="text-[#dfe4fe]/60 hover:text-[#53ddfc] transition-colors">聊天</router-link>
        <router-link to="/sessions" class="text-[#dfe4fe]/60 hover:text-[#53ddfc] transition-colors">会话</router-link>
      </div>
    </div>
    <div class="flex items-center gap-4">
      <button class="material-symbols-outlined text-[#dfe4fe]/60 hover:text-[#53ddfc] transition-colors cursor-pointer">notifications</button>
      <button class="material-symbols-outlined text-[#dfe4fe]/60 hover:text-[#53ddfc] transition-colors cursor-pointer">settings</button>
      <img src="https://lh3.googleusercontent.com/aida-public/AB6AXuBfY-DfXhCEGL4Uq_LyIRvYFgBEJO_5os8c0qrjattoYornl1SI_ofMRlifDLY9h9AxuVkqOpo_8_NHpcfKosQovls6iQXxLFSuB-g5vZ0Pd0nUKMTsC2kDdOxcUOL_NJuBIo44AVrnqAQ9ac_KVdLQWzjZRIWgUZyozjgZNgbVIeos6Ct57GJU4fXvtWO0AzmPf2FNrgDPRq_7zpe3q1qkA6TRpHZAAr0pW0spE6jl0B_x5hWnPBH6sXY0KhueS1BMpaSXxxP4Xs0" class="w-8 h-8 rounded-full border border-primary/30 cursor-pointer" alt="Avatar"/>
    </div>
  </nav>
</template>

<script setup lang="ts">
// 顶部导航栏组件
</script>
