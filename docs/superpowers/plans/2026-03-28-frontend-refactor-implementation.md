# JobClaw 前端重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 JobClaw 现有单文件 HTML 前端重构为模块化 Vue 3 架构，实现所有 Web API 对应的管理界面。

**Architecture:** 采用 Vite + Vue 3 + Pinia + Vue Router 的现代前端架构，按功能模块划分组件，使用 Tailwind CSS 保持现有赛博朋克设计风格。项目位于独立 `ui/` 目录，构建产物输出到 `src/main/resources/static`。

**Tech Stack:** Vite 6, Vue 3.4, Vue Router 4, Pinia 2, Tailwind CSS 3, Axios 1, ECharts 5, VeeValidate 4, Vitest 2

---

## 文件结构总览

```
JobClaw/
├── src/main/resources/static/    # 当前单文件实现（保留至迁移完成）
└── ui/                           # 新的前端项目
    ├── src/
    │   ├── main.ts
    │   ├── App.vue
    │   ├── components/
    │   │   ├── Layout/
    │   │   │   ├── AppLayout.vue
    │   │   │   ├── TopNav.vue
    │   │   │   └── SideNav.vue
    │   │   ├── Chat/
    │   │   │   ├── ChatWindow.vue
    │   │   │   ├── MessageList.vue
    │   │   │   ├── MessageInput.vue
    │   │   │   └── ToolCallPanel.vue
    │   │   ├── common/
    │   │   │   ├── Card.vue
    │   │   │   ├── Button.vue
    │   │   │   ├── Input.vue
    │   │   │   ├── Modal.vue
    │   │   │   ├── Toast.vue
    │   │   │   └── Skeleton.vue
    │   │   └── Dashboard/
    │   ├── views/
    │   │   ├── Dashboard.vue
    │   │   ├── Chat.vue
    │   │   ├── SessionDetail.vue
    │   │   ├── Channels.vue
    │   │   ├── Providers.vue
    │   │   ├── Models.vue
    │   │   ├── AgentConfig.vue
    │   │   ├── Sessions.vue
    │   │   ├── CronJobs.vue
    │   │   ├── Skills.vue
    │   │   ├── MCPServers.vue
    │   │   ├── WorkspaceFiles.vue
    │   │   ├── TokenStats.vue
    │   │   ├── Settings.vue
    │   │   └── Login.vue
    │   ├── router/
    │   │   └── index.ts
    │   ├── stores/
    │   │   ├── index.ts
    │   │   ├── auth.ts
    │   │   ├── chat.ts
    │   │   ├── toast.ts
    │   │   ├── loading.ts
    │   │   └── settings.ts
    │   ├── api/
    │   │   ├── index.ts
    │   │   ├── auth.ts
    │   │   ├── chat.ts
    │   │   ├── sessions.ts
    │   │   ├── channels.ts
    │   │   ├── providers.ts
    │   │   ├── models.ts
    │   │   ├── agent.ts
    │   │   ├── cron.ts
    │   │   ├── skills.ts
    │   │   ├── mcp.ts
    │   │   ├── files.ts
    │   │   └── stats.ts
    │   ├── types/
    │   │   └── index.ts
    │   ├── composables/
    │   │   ├── useToast.ts
    │   │   └── useChatStream.ts
    │   └── styles/
    │       └── main.css
    ├── public/
    ├── index.html
    ├── vite.config.ts
    ├── tailwind.config.js
    ├── tsconfig.json
    ├── vitest.config.ts
    └── package.json
```

---

## 阶段 1：项目初始化（Day 1）

### Task 1: 创建 Vite + Vue 3 + TypeScript 项目

**Files:**
- Create: `ui/package.json`
- Create: `ui/vite.config.ts`
- Create: `ui/tsconfig.json`
- Create: `ui/index.html`

- [ ] **Step 1: 创建 package.json**

```json
{
  "name": "jobclaw-ui",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build",
    "preview": "vite preview",
    "test": "vitest"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "vue-router": "^4.3.0",
    "pinia": "^2.1.7",
    "axios": "^1.6.0",
    "echarts": "^5.4.3",
    "vee-validate": "^4.12.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^6.0.0",
    "vue-tsc": "^1.8.0",
    "typescript": "^5.3.0",
    "tailwindcss": "^3.4.0",
    "vitest": "^2.0.0",
    "@vue/test-utils": "^2.4.0",
    "jsdom": "^24.0.0"
  }
}
```

- [ ] **Step 2: 创建 vite.config.ts**

```typescript
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
});
```

- [ ] **Step 3: 创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "preserve",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src/**/*.ts", "src/**/*.tsx", "src/**/*.vue"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

- [ ] **Step 4: 创建 index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>JobClaw - AI Agent Console</title>
    <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;600;700&family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet"/>
    <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:wght,FILL@100..700,0..1&display=swap" rel="stylesheet"/>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 5: 安装依赖**

```bash
cd ui
npm install
```

Expected: 成功安装所有依赖

- [ ] **Step 6: 提交**

```bash
git add ui/package.json ui/vite.config.ts ui/tsconfig.json ui/index.html
git commit -m "feat: initialize Vite + Vue 3 + TypeScript project"
```

---

### Task 2: 配置 Tailwind CSS

**Files:**
- Create: `ui/tailwind.config.js`
- Create: `ui/src/styles/main.css`
- Modify: `ui/index.html` (已创建)

- [ ] **Step 1: 创建 tailwind.config.js**

```javascript
/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        'surface-container-lowest': '#000000',
        'secondary': '#53ddfc',
        'error-dim': '#d7383b',
        'surface-container-low': '#0c1326',
        'surface-container-highest': '#1c253e',
        'secondary-fixed-dim': '#48d4f3',
        'on-primary-fixed': '#000000',
        'on-background': '#dfe4fe',
        'surface-bright': '#222b47',
        'tertiary-container': '#dae6fe',
        'background': '#070d1f',
        'secondary-fixed': '#65e1ff',
        'on-primary-container': '#2b006e',
        'inverse-primary': '#6e3bd7',
        'surface-container': '#11192e',
        'on-secondary-fixed': '#003a45',
        'outline-variant': '#41475b',
        'primary-fixed': '#ae8dff',
        'on-tertiary-fixed-variant': '#535e72',
        'secondary-dim': '#40ceed',
        'surface-variant': '#1c253e',
        'on-error': '#490006',
        'on-tertiary-fixed': '#374155',
        'error': '#ff716c',
        'surface-container-high': '#171f36',
        'on-tertiary-container': '#495468',
        'inverse-surface': '#faf8ff',
        'surface-tint': '#ba9eff',
        'tertiary-dim': '#ccd7ef',
        'on-surface': '#dfe4fe',
        'on-surface-variant': '#a5aac2',
        'tertiary-fixed': '#dae6fe',
        'on-tertiary': '#515c70',
        'inverse-on-surface': '#4f5469',
        'surface': '#070d1f',
        'primary': '#ba9eff',
        'outline': '#6f758b',
        'on-error-container': '#ffa8a3',
        'surface-dim': '#070d1f',
        'on-secondary-fixed-variant': '#005969',
        'on-primary-fixed-variant': '#370086',
        'primary-dim': '#8455ef',
        'tertiary': '#f0f3ff',
        'primary-fixed-dim': '#a27cff',
        'on-primary': '#39008c',
        'tertiary-fixed-dim': '#ccd7ef',
        'primary-container': '#ae8dff',
        'on-secondary': '#004b58',
        'error-container': '#9f0519',
        'on-secondary-container': '#ecfaff',
        'secondary-container': '#00687a'
      },
      fontFamily: {
        headline: ['Space Grotesk'],
        body: ['Inter'],
        label: ['Inter'],
        mono: ['JetBrains Mono']
      },
      borderRadius: {
        DEFAULT: '0.125rem',
        lg: '0.25rem',
        xl: '0.5rem',
        full: '0.75rem'
      }
    }
  },
  plugins: []
}
```

- [ ] **Step 2: 创建 src/styles/main.css**

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

/* 全局样式 */
body {
  @apply bg-background text-on-background font-body min-h-screen;
}

/* 毛玻璃效果 */
.glass-panel {
  @apply bg-[rgba(23,31,54,0.4)] backdrop-blur-xl;
}

/* 扫描线叠加 */
.scanline-overlay {
  background: linear-gradient(to bottom, transparent 50%, rgba(83, 221, 252, 0.02) 50%);
  background-size: 100% 4px;
  pointer-events: none;
}

/* 终端光标 */
.terminal-cursor {
  display: inline-block;
  width: 8px;
  height: 18px;
  background-color: #53ddfc;
  margin-left: 4px;
  vertical-align: middle;
}

/* 自定义滚动条 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  @apply bg-[rgba(17,25,46,0.5)];
}

::-webkit-scrollbar-thumb {
  @apply bg-[rgba(111,117,139,0.5)] rounded;
}

::-webkit-scrollbar-thumb:hover {
  @apply bg-[rgba(111,117,139,0.7)];
}
```

- [ ] **Step 3: 更新 index.html 添加 Tailwind 指令引用**

在 index.html 的 `<head>` 中添加：
```html
<script src="https://cdn.tailwindcss.com"></script>
```

注意：开发阶段使用 CDN，构建时使用 npm 包。

- [ ] **Step 4: 提交**

```bash
git add ui/tailwind.config.js ui/src/styles/main.css
git commit -m "feat: configure Tailwind CSS with cyberpunk theme"
```

---

### Task 3: 配置 Vue Router 和 Pinia

**Files:**
- Create: `ui/src/router/index.ts`
- Create: `ui/src/stores/index.ts`
- Create: `ui/src/main.ts`
- Create: `ui/src/App.vue`

- [ ] **Step 1: 创建 router/index.ts**

```typescript
import { createRouter, createWebHistory } from 'vue-router';

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('@/components/Layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue') },
      { path: 'chat', name: 'Chat', component: () => import('@/views/Chat.vue') },
      { path: 'sessions', name: 'Sessions', component: () => import('@/views/Sessions.vue') },
      { path: 'sessions/:id', name: 'SessionDetail', component: () => import('@/views/SessionDetail.vue'), props: true },
      { path: 'channels', name: 'Channels', component: () => import('@/views/Channels.vue') },
      { path: 'providers', name: 'Providers', component: () => import('@/views/Providers.vue') },
      { path: 'models', name: 'Models', component: () => import('@/views/Models.vue') },
      { path: 'agent', name: 'AgentConfig', component: () => import('@/views/AgentConfig.vue') },
      { path: 'cron', name: 'CronJobs', component: () => import('@/views/CronJobs.vue') },
      { path: 'skills', name: 'Skills', component: () => import('@/views/Skills.vue') },
      { path: 'mcp', name: 'MCPServers', component: () => import('@/views/MCPServers.vue') },
      { path: 'files', name: 'WorkspaceFiles', component: () => import('@/views/WorkspaceFiles.vue') },
      { path: 'stats', name: 'TokenStats', component: () => import('@/views/TokenStats.vue') },
      { path: 'settings', name: 'Settings', component: () => import('@/views/Settings.vue') }
    ]
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

// 路由守卫
router.beforeEach((to, from, next) => {
  const isAuthenticated = localStorage.getItem('auth_token') !== null;

  if (to.meta.requiresAuth !== false && !isAuthenticated) {
    next('/login');
  } else if (to.path === '/login' && isAuthenticated) {
    next('/dashboard');
  } else {
    next();
  }
});

export default router;
```

- [ ] **Step 2: 创建 stores/index.ts**

```typescript
import { createPinia } from 'pinia';

const pinia = createPinia();

export default pinia;
```

- [ ] **Step 3: 创建 main.ts**

```typescript
import { createApp } from 'vue';
import { useToastStore } from '@/stores/toast';
import App from './App.vue';
import router from './router';
import pinia from './stores';
import './styles/main.css';

const app = createApp(App);

// 全局错误处理
app.config.errorHandler = (err, instance, info) => {
  console.error('Global error:', err, info);
  const toastStore = useToastStore();
  if (toastStore) {
    toastStore.error('发生错误：' + (err as Error).message);
  }
};

// 未捕获的 Promise 错误
window.addEventListener('unhandledrejection', event => {
  console.error('Unhandled promise rejection:', event.reason);
  const toastStore = useToastStore();
  if (toastStore) {
    toastStore.error('操作失败：' + (event.reason as Error).message);
  }
});

app.use(pinia);
app.use(router);
app.mount('#app');
```

- [ ] **Step 4: 创建 App.vue**

```vue
<template>
  <router-view />
</template>

<script setup lang="ts">
// 根组件，仅渲染路由视图
</script>
```

- [ ] **Step 5: 提交**

```bash
git add ui/src/router ui/src/stores ui/src/main.ts ui/src/App.vue
git commit -m "feat: configure Vue Router and Pinia"
```

---

### Task 4: 创建 API 服务层

**Files:**
- Create: `ui/src/api/index.ts`
- Create: `ui/src/api/auth.ts`
- Create: `ui/src/api/chat.ts`
- Create: `ui/src/api/sessions.ts`
- Create: `ui/src/types/index.ts`

- [ ] **Step 1: 创建 types/index.ts**

```typescript
// 通用 API 响应
export interface ApiResponse<T = any> {
  success: boolean;
  data?: T;
  error?: string;
  message?: string;
}

// 消息类型
export interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: string;
  toolCall?: ToolCall;
}

// 工具调用类型
export interface ToolCall {
  toolId: string;
  toolName: string;
  status: 'running' | 'success' | 'error';
  duration: number;
  result: any;
  parameters: string;
  _expanded: boolean;
}

// 会话类型
export interface SessionInfo {
  key: string;
  created: string;
  updated: string;
  message_count: number;
}
```

- [ ] **Step 2: 创建 api/index.ts**

```typescript
import axios from 'axios';
import type { AxiosError, InternalAxiosRequestConfig, AxiosResponse } from 'axios';

const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// 请求拦截器
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('auth_token');
    if (token && token !== 'auth-disabled') {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// 响应拦截器
apiClient.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('auth_token');
      window.location.href = '/login';
    } else if (error.response?.status === 403) {
      console.error('没有权限执行此操作');
    } else if (error.response?.status >= 500) {
      console.error('服务器错误:', error.response.status);
    }
    return Promise.reject(error);
  }
);

export default apiClient;
```

- [ ] **Step 3: 创建 api/auth.ts**

```typescript
import apiClient from './index';
import type { ApiResponse } from '@/types';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  success: boolean;
  token?: string;
  error?: string;
}

export const authApi = {
  login(username: string, password: string): Promise<LoginResponse> {
    return apiClient.post('/auth/login', { username, password }).then(res => res.data);
  },

  checkAuth(): Promise<ApiResponse> {
    return apiClient.get('/auth/check').then(res => res.data);
  }
};
```

- [ ] **Step 4: 创建 api/sessions.ts**

```typescript
import apiClient from './index';
import type { SessionInfo, Message } from '@/types';

export const sessionsApi = {
  list(): Promise<SessionInfo[]> {
    return apiClient.get('/sessions').then(res => res.data);
  },

  getDetail(key: string): Promise<SessionInfo> {
    return apiClient.get(`/sessions/${key}`).then(res => res.data);
  },

  delete(key: string): Promise<void> {
    return apiClient.delete(`/sessions/${key}`).then(res => res.data);
  },

  // 本地存储消息（简单模式）
  saveMessages(key: string, messages: Message[]): void {
    localStorage.setItem(`session:${key}:messages`, JSON.stringify(messages));
  },

  getMessages(key: string): Message[] {
    const stored = localStorage.getItem(`session:${key}:messages`);
    return stored ? JSON.parse(stored) : [];
  }
};
```

- [ ] **Step 5: 创建 api/chat.ts**

```typescript
import apiClient from './index';
import type { Message } from '@/types';

export interface ChatRequest {
  message: string;
  sessionKey?: string;
}

export interface ChatResponse {
  success: boolean;
  message?: string;
  error?: string;
  session?: string;
}

export const chatApi = {
  send(message: string, sessionKey?: string): Promise<ChatResponse> {
    return apiClient.post('/chat', { message, sessionKey }).then(res => res.data);
  },

  sendStream(message: string, sessionKey?: string): Promise<Response> {
    return fetch('/api/execute/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, sessionKey })
    });
  }
};
```

- [ ] **Step 6: 提交**

```bash
git add ui/src/api ui/src/types
git commit -m "feat: create API service layer"
```

---

### Task 5: 创建 Stores

**Files:**
- Create: `ui/src/stores/auth.ts`
- Create: `ui/src/stores/toast.ts`
- Create: `ui/src/stores/loading.ts`
- Create: `ui/src/stores/chat.ts`

- [ ] **Step 1: 创建 stores/auth.ts**

```typescript
import { defineStore } from 'pinia';
import { authApi } from '@/api/auth';

interface AuthState {
  isAuthenticated: boolean;
  token: string | null;
  authEnabled: boolean;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    isAuthenticated: localStorage.getItem('auth_token') !== null,
    token: localStorage.getItem('auth_token'),
    authEnabled: true
  }),

  actions: {
    async login(username: string, password: string) {
      const response = await authApi.login(username, password);
      if (response.success && response.token) {
        this.token = response.token;
        this.isAuthenticated = true;
        localStorage.setItem('auth_token', response.token);
      }
      return response;
    },

    logout() {
      this.token = null;
      this.isAuthenticated = false;
      localStorage.removeItem('auth_token');
    }
  }
});
```

- [ ] **Step 2: 创建 stores/toast.ts**

```typescript
import { defineStore } from 'pinia';

interface Toast {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
  duration?: number;
}

export const useToastStore = defineStore('toast', {
  state: () => ({
    toasts: [] as Toast[]
  }),

  actions: {
    add(toast: Omit<Toast, 'id'>) {
      const id = 'toast_' + Date.now();
      const newToast = { ...toast, id };
      this.toasts.push(newToast);

      setTimeout(() => {
        this.remove(id);
      }, toast.duration || 5000);

      return id;
    },

    remove(id: string) {
      this.toasts = this.toasts.filter(t => t.id !== id);
    },

    success(message: string) {
      return this.add({ type: 'success', message });
    },

    error(message: string) {
      return this.add({ type: 'error', message });
    },

    warning(message: string) {
      return this.add({ type: 'warning', message });
    },

    info(message: string) {
      return this.add({ type: 'info', message });
    }
  }
});
```

- [ ] **Step 3: 创建 stores/loading.ts**

```typescript
import { defineStore } from 'pinia';

export const useLoadingStore = defineStore('loading', {
  state: () => ({
    global: false,
    counters: {} as Record<string, number>
  }),

  actions: {
    start(key: string = 'global') {
      if (!this.counters[key]) {
        this.counters[key] = 0;
      }
      this.counters[key]++;
      this.updateGlobal();
    },

    end(key: string = 'global') {
      if (this.counters[key]) {
        this.counters[key]--;
      }
      this.updateGlobal();
    },

    updateGlobal() {
      this.global = Object.values(this.counters).some(c => c > 0);
    }
  }
});
```

- [ ] **Step 4: 创建 stores/chat.ts**

```typescript
import { defineStore } from 'pinia';
import type { Message, ToolCall } from '@/types';

interface SessionSummary {
  key: string;
  title: string;
  preview: string;
  lastUpdated: string;
  messageCount: number;
}

interface ChatState {
  currentSessionKey: string | null;
  sessions: SessionSummary[];
  messages: Message[];
  isStreaming: boolean;
  isConnected: boolean;
  currentStreamingMessageId: string | null;
  isSidebarOpen: boolean;
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => ({
    currentSessionKey: null,
    sessions: [],
    messages: [],
    isStreaming: false,
    isConnected: false,
    currentStreamingMessageId: null,
    isSidebarOpen: true
  }),

  actions: {
    createNewSession() {
      const newKey = 'web:' + Date.now();
      this.currentSessionKey = newKey;
      this.messages = [];
    },

    addMessage(role: string, content: string, toolCall?: ToolCall) {
      const msg = {
        id: 'msg_' + Date.now(),
        role,
        content,
        timestamp: new Date().toISOString(),
        toolCall
      };
      this.messages.push(msg);
    },

    appendToLastAssistantMessage(content: string) {
      const lastMsg = this.messages.slice().reverse().find(m =>
        m.role === 'assistant' && !m.toolCall
      );
      if (lastMsg) {
        lastMsg.content += content;
      } else {
        this.addMessage('assistant', content);
      }
    },

    toggleSidebar() {
      this.isSidebarOpen = !this.isSidebarOpen;
    }
  }
});
```

- [ ] **Step 5: 提交**

```bash
git add ui/src/stores/*.ts
git commit -m "feat: create Pinia stores"
```

---

## 阶段 2：核心组件（Day 2-3）

### Task 6: 创建布局组件

**Files:**
- Create: `ui/src/components/Layout/AppLayout.vue`
- Create: `ui/src/components/Layout/TopNav.vue`
- Create: `ui/src/components/Layout/SideNav.vue`

- [ ] **Step 1: 创建 TopNav.vue**

```vue
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
      <button class="material-symbols-outlined text-[#dfe4fe]/60 hover:text-[#53ddfc] transition-colors">notifications</button>
      <button class="material-symbols-outlined text-[#dfe4fe]/60 hover:text-[#53ddfc] transition-colors">settings</button>
      <img src="https://lh3.googleusercontent.com/aida-public/AB6AXuBfY-DfXhCEGL4Uq_LyIRvYFgBEJO_5os8c0qrjattoYornl1SI_ofMRlifDLY9h9AxuVkqOpo_8_NHpcfKosQovls6iQXxLFSuB-g5vZ0Pd0nUKMTsC2kDdOxcUOL_NJuBIo44AVrnqAQ9ac_KVdLQWzjZRIWgUZyozjgZNgbVIeos6Ct57GJU4fXvtWO0AzmPf2FNrgDPRq_7zpe3q1qkA6TRpHZAAr0pW0spE6jl0B_x5hWnPBH6sXY0KhueS1BMpaSXxxP4Xs0" class="w-8 h-8 rounded-full border border-primary/30 cursor-pointer"/>
    </div>
  </nav>
</template>

<script setup lang="ts">
// 顶部导航栏组件
</script>
```

- [ ] **Step 2: 创建 SideNav.vue**

```vue
<template>
  <aside class="fixed left-0 top-16 h-[calc(100vh-64px)] w-64 bg-[#0c1326]/90 backdrop-blur-lg border-r border-[#41475b]/15 flex flex-col py-8 gap-4 z-40">
    <div class="px-6 mb-4">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-lg bg-primary/20 flex items-center justify-center border border-primary/30">
          <span class="material-symbols-outlined text-primary">hub</span>
        </div>
        <div>
          <h3 class="text-sm font-bold text-on-surface font-headline leading-tight">JobClaw System</h3>
          <p class="text-[10px] font-mono tracking-widest uppercase">● Online</p>
        </div>
      </div>
    </div>
    <nav class="flex-1 space-y-1">
      <router-link to="/dashboard" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] transition-all text-sm">仪表盘</router-link>
      <router-link to="/chat" class="flex items-center gap-4 px-6 py-3 text-[#ba9eff] border-l-2 border-[#53ddfc] bg-[#171f36]/40 transition-all text-sm">聊天</router-link>
      <router-link to="/sessions" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] transition-all text-sm">会话</router-link>
      <router-link to="/channels" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] transition-all text-sm">通道</router-link>
      <router-link to="/providers" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] transition-all text-sm">Provider</router-link>
      <router-link to="/settings" class="flex items-center gap-4 px-6 py-3 text-[#dfe4fe]/50 hover:text-[#dfe4fe] transition-all text-sm">设置</router-link>
    </nav>
  </aside>
</template>

<script setup lang="ts">
// 侧边导航栏组件
</script>
```

- [ ] **Step 3: 创建 AppLayout.vue**

```vue
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
```

- [ ] **Step 4: 提交**

```bash
git add ui/src/components/Layout
git commit -m "feat: create layout components"
```

---

### Task 7: 创建通用组件

**Files:**
- Create: `ui/src/components/common/Button.vue`
- Create: `ui/src/components/common/Input.vue`
- Create: `ui/src/components/common/Card.vue`
- Create: `ui/src/components/common/Modal.vue`
- Create: `ui/src/components/common/Toast.vue`
- Create: `ui/src/components/common/Skeleton.vue`

- [ ] **Step 1: 创建 Button.vue**

```vue
<template>
  <button
    :class="[
      'px-4 py-2 rounded font-bold transition-all text-sm',
      variant === 'primary' ? 'bg-primary text-on-primary-fixed' : '',
      variant === 'secondary' ? 'bg-secondary text-surface-container-lowest' : '',
      variant === 'ghost' ? 'bg-surface-container-high text-outline hover:bg-surface-container-highest' : '',
      disabled ? 'opacity-50 cursor-not-allowed' : ''
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
```

- [ ] **Step 2: 创建 Input.vue**

```vue
<template>
  <input
    :value="modelValue"
    :type="type"
    :placeholder="placeholder"
    :disabled="disabled"
    class="bg-surface-container-high border-none text-sm px-4 py-2 rounded text-on-surface outline-none focus:ring-1 focus:ring-secondary/50 placeholder:text-outline-variant disabled:opacity-50"
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
```

- [ ] **Step 3: 创建 Card.vue**

```vue
<template>
  <div class="glass-panel p-4 rounded-lg border border-outline-variant/20 shadow-[inset_0_1px_4px_rgba(255,255,255,0.05)]">
    <slot />
  </div>
</template>

<script setup lang="ts">
// 卡片容器组件
</script>
```

- [ ] **Step 4: 创建 Modal.vue**

```vue
<template>
  <Teleport to="body">
    <div v-if="modelValue" class="fixed inset-0 z-50 flex items-center justify-center">
      <div class="absolute inset-0 bg-black/50" @click="$emit('update:modelValue', false)"></div>
      <div class="relative glass-panel p-6 rounded-lg border border-outline-variant/20 max-w-md w-full mx-4 z-10">
        <slot />
        <button class="absolute top-2 right-2 material-symbols-outlined text-on-surface/60 hover:text-on-surface" @click="$emit('update:modelValue', false)">close</button>
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
```

- [ ] **Step 5: 创建 Toast.vue**

```vue
<template>
  <div class="fixed top-20 right-6 w-72 space-y-3 z-50">
    <TransitionGroup name="slide">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        :class="[
          'glass-panel p-4 border-l-4 rounded-r-lg shadow-xl',
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
</style>
```

- [ ] **Step 6: 创建 Skeleton.vue**

```vue
<template>
  <div class="skeleton" :class="[`skeleton--${type}`]">
    <template v-if="type === 'text'">
      <div class="skeleton-line" v-for="i in lines" :key="i"></div>
    </template>
    <template v-else-if="type === 'card'">
      <div class="skeleton-card-image"></div>
      <div class="skeleton-card-content">
        <div class="skeleton-line short"></div>
        <div class="skeleton-line"></div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  type: 'text' | 'card' | 'table';
  lines?: number;
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
  @apply h-4 rounded mb-2 bg-[rgba(255,255,255,0.1)];
}

.skeleton-line.short {
  @apply w-1/2;
}
</style>
```

- [ ] **Step 7: 提交**

```bash
git add ui/src/components/common
git commit -m "feat: create common UI components"
```

---

## 阶段 3：页面实现（Day 4-7）

### Task 8: 创建 Login 页面

**Files:**
- Create: `ui/src/views/Login.vue`
- Test: `ui/src/views/__tests__/Login.test.ts`

- [ ] **Step 1: 编写测试**

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import Login from '../Login.vue';

describe('Login', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('renders login form', () => {
    const wrapper = mount(Login);
    expect(wrapper.find('input[type="text"]').exists()).toBe(true);
    expect(wrapper.find('input[type="password"]').exists()).toBe(true);
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

```bash
npm run test -- src/views/__tests__/Login.test.ts
```

Expected: FAIL (组件不存在)

- [ ] **Step 3: 创建 Login.vue**

```vue
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
```

- [ ] **Step 4: 运行测试验证通过**

```bash
npm run test -- src/views/__tests__/Login.test.ts
```

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add ui/src/views/Login.vue
git commit -m "feat: create Login page"
```

---

### Task 9: 创建 Dashboard 页面

**Files:**
- Create: `ui/src/views/Dashboard.vue`

- [ ] **Step 1: 创建 Dashboard.vue**

```vue
<template>
  <div class="p-8">
    <h1 class="text-2xl font-bold text-on-surface font-headline mb-6">仪表盘</h1>

    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-primary/20 flex items-center justify-center border border-primary/30">
            <span class="material-symbols-outlined text-primary">hub</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">Online</div>
            <div class="text-sm text-on-surface-variant">系统状态</div>
          </div>
        </div>
      </Card>

      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-secondary/20 flex items-center justify-center border border-secondary/30">
            <span class="material-symbols-outlined text-secondary">forum</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ sessionCount }}</div>
            <div class="text-sm text-on-surface-variant">活跃会话</div>
          </div>
        </div>
      </Card>

      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-tertiary/20 flex items-center justify-center border border-tertiary/30">
            <span class="material-symbols-outlined text-tertiary">smart_toy</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ currentModel }}</div>
            <div class="text-sm text-on-surface-variant">当前模型</div>
          </div>
        </div>
      </Card>

      <Card class="p-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-lg bg-error/20 flex items-center justify-center border border-error/30">
            <span class="material-symbols-outlined text-error">workspaces</span>
          </div>
          <div>
            <div class="text-2xl font-bold text-on-surface">{{ workspacePath }}</div>
            <div class="text-sm text-on-surface-variant">工作空间</div>
          </div>
        </div>
      </Card>
    </div>

    <Card class="p-6">
      <h2 class="text-lg font-bold text-on-surface mb-4">快捷操作</h2>
      <div class="flex gap-4">
        <router-link to="/chat">
          <Button variant="primary">新建对话</Button>
        </router-link>
        <router-link to="/sessions">
          <Button variant="secondary">查看会话</Button>
        </router-link>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import Card from '@/components/common/Card.vue';
import Button from '@/components/common/Button.vue';

const sessionCount = ref(0);
const currentModel = ref('-');
const workspacePath = ref('-');

onMounted(async () => {
  // 加载系统状态
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    sessionCount.value = data.sessions || 0;
    currentModel.value = data.model || '-';
    workspacePath.value = data.workspace || '-';
  } catch (e) {
    console.error('Failed to load status:', e);
  }
});
</script>
```

- [ ] **Step 2: 提交**

```bash
git add ui/src/views/Dashboard.vue
git commit -m "feat: create Dashboard page"
```

---

### Task 10: 创建 Chat 页面

**Files:**
- Create: `ui/src/views/Chat.vue`
- Create: `ui/src/components/Chat/ChatWindow.vue`
- Create: `ui/src/components/Chat/MessageList.vue`
- Create: `ui/src/components/Chat/MessageInput.vue`
- Create: `ui/src/components/Chat/ToolCallPanel.vue`
- Create: `ui/src/composables/useChatStream.ts`

（由于 Chat 页面较为复杂，将拆分为多个子组件实现）

- [ ] **Step 1: 创建 useChatStream.ts**

```typescript
import { ref, onUnmounted, computed } from 'vue';
import { useChatStore } from '@/stores/chat';
import { useToast } from './useToast';

export function useChatStream() {
  const chatStore = useChatStore();
  const toast = useToast();
  const abortController = ref<AbortController | null>(null);

  const startStream = async (message: string) => {
    abortController.value = new AbortController();

    try {
      const response = await fetch('/api/execute/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message, sessionKey: chatStore.currentSessionKey }),
        signal: abortController.value.signal
      });

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value, { stream: false });
        buffer += chunk;

        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        let currentEvent: string | null = null;

        for (const line of lines) {
          if (!line.trim() || line.trim().startsWith(':')) continue;

          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
            continue;
          }

          if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim();
            try {
              const data = JSON.parse(dataStr);
              handleStreamData(data, currentEvent);
            } catch (e) {
              console.error('JSON parse error:', e);
            }
            currentEvent = null;
          }
        }
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') {
        console.log('Stream aborted');
      } else {
        toast.error('流式输出错误：' + (error as Error).message);
        chatStore.isStreaming = false;
      }
    }
  };

  const handleStreamData = (data: any, eventType: string | null) => {
    // 处理 SSE 数据
    if (eventType === 'THINK_STREAM') {
      chatStore.appendToLastAssistantMessage(data.content || '');
    } else if (eventType === 'THINK_START') {
      chatStore.addMessage('assistant', '');
    } else if (eventType === 'FINAL_RESPONSE') {
      chatStore.isStreaming = false;
    }
    // 更多事件类型处理...
  };

  const stopStream = () => {
    abortController.value?.abort();
  };

  onUnmounted(() => {
    stopStream();
  });

  return {
    startStream,
    stopStream,
    isStreaming: computed(() => chatStore.isStreaming)
  };
}
```

（由于内容较长，其余组件在后续任务中创建）

---

## 阶段 4：API 服务完善（Day 8）

### Task 11: 创建其余 API 服务

**Files:**
- Create: `ui/src/api/channels.ts`
- Create: `ui/src/api/providers.ts`
- Create: `ui/src/api/models.ts`
- Create: `ui/src/api/agent.ts`
- Create: `ui/src/api/cron.ts`
- Create: `ui/src/api/skills.ts`
- Create: `ui/src/api/mcp.ts`
- Create: `ui/src/api/files.ts`
- Create: `ui/src/api/stats.ts`

（每个 API 服务模块参考 design doc 中的定义）

---

## 阶段 5：其余页面实现（Day 9-12）

### Task 12-22: 创建其余页面

| Task | 页面 | Files |
|------|------|-------|
| 12 | Sessions.vue | `ui/src/views/Sessions.vue` |
| 13 | SessionDetail.vue | `ui/src/views/SessionDetail.vue` |
| 14 | Channels.vue | `ui/src/views/Channels.vue` |
| 15 | Providers.vue | `ui/src/views/Providers.vue` |
| 16 | Models.vue | `ui/src/views/Models.vue` |
| 17 | AgentConfig.vue | `ui/src/views/AgentConfig.vue` |
| 18 | CronJobs.vue | `ui/src/views/CronJobs.vue` |
| 19 | Skills.vue | `ui/src/views/Skills.vue` |
| 20 | MCPServers.vue | `ui/src/views/MCPServers.vue` |
| 21 | WorkspaceFiles.vue | `ui/src/views/WorkspaceFiles.vue` |
| 22 | TokenStats.vue | `ui/src/views/TokenStats.vue` |
| 23 | Settings.vue | `ui/src/views/Settings.vue` |

---

## 阶段 6：测试和部署（Day 13-14）

### Task 24: 配置 Vitest 单元测试

**Files:**
- Create: `ui/vitest.config.ts`
- Create: `ui/src/stores/__tests__/auth.test.ts`
- Create: `ui/src/components/__tests__/Button.test.ts`

### Task 25: 构建和部署验证

- [ ] **Step 1: 运行构建**

```bash
npm run build
```

- [ ] **Step 2: 验证 Spring Boot 集成**

```bash
# 启动 Spring Boot 应用
java -jar target/jobclaw-1.0.0.jar gateway

# 访问 http://localhost:18791 验证前端加载正常
```

---

## 完成标准

- [ ] 所有 14 个页面实现完成
- [ ] 所有组件通过 Vitest 测试
- [ ] 构建成功并集成到 Spring Boot
- [ ] 保持现有赛博朋克设计风格
- [ ] 历史会话和对话查看功能正常工作
- [ ] SSE 流式输出正常工作

---

**预估总工作量：** 14 天（按每日 8 小时计，约 112 小时）
