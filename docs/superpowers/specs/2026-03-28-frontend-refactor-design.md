# JobClaw 前端重构设计文档

**创建日期：** 2026-03-28
**状态：** 草稿
**作者：** Claude (JobClaw Team)

---

## 1. 概述

### 1.1 项目背景

JobClaw 当前前端采用单文件 HTML 实现（`src/main/resources/static/index.html`），所有 HTML/CSS/JS 代码耦合在一个文件中。虽然功能完整，但存在以下问题：

- 代码难以维护和扩展
- 无法复用组件
- 没有路由系统，页面切换困难
- 状态管理分散
- 构建和部署不够灵活

### 1.2 改造目标

将前端重构为模块化架构，使用现代化的 Vue 3 技术栈，实现：

1. **组件化开发** - 每个功能模块作为独立的 Vue 单文件组件
2. **路由管理** - 使用 Vue Router 管理页面导航
3. **状态管理** - 使用 Pinia 统一管理应用状态
4. **API 服务层** - 封装所有 Web API 调用
5. **保持设计一致性** - 保留现有 Tailwind CSS 设计系统和赛博朋克风格

### 1.3 范围

**本次重构覆盖所有 Web API 对应的前端管理界面：**

| 模块 | 功能 | 优先级 |
|------|------|--------|
| Dashboard | 系统概览、状态卡片 | P0 |
| Chat | 聊天界面（现有功能迁移+增强） | P0 |
| Channels | 通道列表、启用/禁用、配置 | P1 |
| Providers | LLM Provider 配置、API Key 管理 | P1 |
| Models | 模型列表、模型选择 | P1 |
| Agent Config | Agent 配置、参数调整 | P1 |
| Sessions | 会话管理、查看/删除 | P2 |
| Cron Jobs | 定时任务创建、编辑、启用/禁用 | P2 |
| Skills | 技能浏览、创建、编辑、删除 | P2 |
| MCP Servers | MCP 服务器连接、配置、测试 | P2 |
| Workspace Files | 文件浏览、读取、编辑、上传 | P2 |
| Token Stats | Token 使用统计、图表展示 | P3 |
| Settings | 系统设置、配置管理 | P2 |
| Login | 登录界面（简单模式） | P0 |

---

## 2. 技术架构

### 2.1 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vite | 6.x | 构建工具 |
| Vue | 3.4+ | UI 框架 |
| Vue Router | 4.x | 路由管理 |
| Pinia | 2.x | 状态管理 |
| Tailwind CSS | 3.x | 样式框架 |
| Axios | 1.x | HTTP 客户端 |
| ECharts | 5.x | 数据可视化（Token Stats） |
| VeeValidate | 4.x | 表单验证 |
| Vitest | 2.x | 单元测试（可选） |

### 2.2 环境变量配置

```env
# .env.development
VITE_API_BASE_URL=http://localhost:8080/api
VITE_APP_TITLE=JobClaw Dev
VITE_DEBUG=true

# .env.production
VITE_API_BASE_URL=/api
VITE_APP_TITLE=JobClaw
VITE_DEBUG=false
```

### 2.3 开发服务器代理

```typescript
// vite.config.ts
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  }
});
```

### 2.4 TypeScript 类型定义

```typescript
// src/types/index.ts

// 通用 API 响应
export interface ApiResponse<T = any> {
  success: boolean;
  data?: T;
  error?: string;
  message?: string;
}

// 分页响应
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// 错误响应
export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}
```

```typescript
// src/types/channel.ts
export interface Channel {
  name: string;
  enabled: boolean;
  token?: string;
  allowFrom?: string[];
}

export interface ChannelConfig extends Channel {
  appId?: string;
  appSecret?: string;
  connectionMode?: 'websocket' | 'webhook';
}
```

```typescript
// src/types/provider.ts
export interface Provider {
  name: string;
  apiBase: string;
  apiKey: string;
  authorized: boolean;
}
```

```typescript
// src/types/model.ts
export interface ModelDefinition {
  name: string;
  provider: string;
  model: string;
  maxContextSize?: number;
  description?: string;
  authorized: boolean;
}
```

```typescript
// src/types/agent.ts
export interface AgentConfig {
  workspace: string;
  model: string;
  maxTokens: number;
  temperature: number;
  maxToolIterations: number;
  heartbeatEnabled: boolean;
  restrictToWorkspace: boolean;
}
```

```typescript
// src/types/cron.ts
export interface CronJob {
  id: string;
  name: string;
  message: string;
  enabled: boolean;
  schedule: string | number;
  channel?: string;
  to?: string;
  nextRun?: number;
}
```

```typescript
// src/types/skill.ts
export interface Skill {
  name: string;
  description: string;
  source: string;
  path: string;
  content?: string;
}
```

```typescript
// src/types/mcp.ts
export interface MCPServer {
  name: string;
  type: 'http' | 'stdio';
  description: string;
  endpoint?: string;
  apiKey?: string;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  enabled: boolean;
  timeout: number;
}
```

```typescript
// src/types/file.ts
export interface FileInfo {
  name: string;
  exists: boolean;
  size: number;
  lastModified: number;
  content?: string;
}
```

### 2.5 表单验证规则

```typescript
// src/validators/index.ts
import { required, email, minLength, maxLength } from '@vuelidate/validators';

export const validators = {
  // 登录表单
  login: {
    username: { required, minLength: minLength(3) },
    password: { required, minLength: minLength(6) }
  },

  // Provider 配置
  providerApiKey: { required, minLength: minLength(10) },

  // Cron 任务
  cronJob: {
    name: { required, minLength: minLength(3), maxLength: maxLength(50) },
    message: { required }
  }
};
```

### 2.7 错误处理和全局通知

**全局错误处理：**

```typescript
// src/main.ts
const app = createApp(App);

// 全局错误处理器
app.config.errorHandler = (err, instance, info) => {
  console.error('Global error:', err, info);

  // 使用 Toast 显示错误
  const toastStore = useToastStore();
  toastStore.error('发生错误：' + (err as Error).message);
};

// 未捕获的 Promise 错误
window.addEventListener('unhandledrejection', event => {
  console.error('Unhandled promise rejection:', event.reason);
  const toastStore = useToastStore();
  toastStore.error('操作失败：' + (event.reason as Error).message);
});
```

**Axios 错误拦截增强：**

```typescript
// src/api/index.ts
apiClient.interceptors.response.use(
  response => response,
  error => {
    const toastStore = useToastStore();

    if (error.response?.status === 401) {
      localStorage.removeItem('auth_token');
      window.location.href = '/login';
    } else if (error.response?.status === 403) {
      toastStore.error('没有权限执行此操作');
    } else if (error.response?.status === 404) {
      toastStore.error('请求的资源不存在');
    } else if (error.response?.status >= 500) {
      toastStore.error('服务器错误：' + error.response.status);
    } else if (error.code === 'ECONNABORTED') {
      toastStore.warning('请求超时，请重试');
    } else {
      toastStore.error(error.message || '操作失败');
    }

    return Promise.reject(error);
  }
);
```

**Toast Store：**

```typescript
// src/stores/toast.ts
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

      // 自动移除
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

**Composable：useToast**

```typescript
// src/composables/useToast.ts
import { useToastStore } from '@/stores/toast';

export function useToast() {
  const store = useToastStore();

  return {
    success: (message: string) => store.success(message),
    error: (message: string) => store.error(message),
    warning: (message: string) => store.warning(message),
    info: (message: string) => store.info(message)
  };
}
```

### 2.8 Loading 状态和骨架屏

**全局 Loading 状态：**

```typescript
// src/stores/loading.ts
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

**骨架屏组件：**

```vue
<!-- src/components/common/Skeleton.vue -->
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
</style>
```

### 2.9 Composables 设计

**useChatStream：**

```typescript
// src/composables/useChatStream.ts
import { ref, onUnmounted } from 'vue';
import { useChatStore } from '@/stores/chat';
import { useToast } from './useToast';

export function useChatStream() {
  const chatStore = useChatStore();
  const toast = useToast();
  const abortController = ref<AbortController | null>(null);

  const startStream = async (message: string) => {
    abortController = new AbortController();

    try {
      const response = await fetch('/api/execute/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message, sessionKey: chatStore.sessionKey }),
        signal: abortController.signal
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
              chatStore.handleStreamData(data, currentEvent);
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

  const stopStream = () => {
    abortController?.abort();
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

### 2.10 项目结构
├── src/main/resources/static/        # 当前单文件实现（保留至迁移完成）
└── ui/                               # 新的前端项目（独立目录）
    ├── src/
    │   ├── main.ts                   # 应用入口
    │   ├── App.vue                   # 根组件
    │   ├── components/               # 可复用组件
    │   │   ├── Layout/
    │   │   │   ├── AppLayout.vue     # 主布局框架
    │   │   │   ├── TopNav.vue        # 顶部导航栏
    │   │   │   ├── SideNav.vue       # 侧边导航栏
    │   │   │   └── index.ts
    │   │   ├── Chat/
    │   │   │   ├── ChatWindow.vue    # 聊天窗口
    │   │   │   ├── MessageList.vue   # 消息列表
    │   │   │   ├── MessageInput.vue  # 输入框
    │   │   │   ├── ToolCallPanel.vue # 工具调用面板
    │   │   │   └── index.ts
    │   │   ├── common/
    │   │   │   ├── Card.vue          # 卡片组件
    │   │   │   ├── Button.vue        # 按钮组件
    │   │   │   ├── Input.vue         # 输入框组件
    │   │   │   ├── Modal.vue         # 弹窗组件
    │   │   │   ├── Toast.vue         # 提示组件
    │   │   │   └── index.ts
    │   │   ├── Dashboard/
    │   │   ├── Channels/
    │   │   ├── Providers/
    │   │   └── ...                   # 各模块组件
    │   ├── views/                    # 页面级组件
    │   │   ├── Dashboard.vue
    │   │   ├── Chat.vue
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
    │   │   └── index.ts              # 路由配置
    │   ├── stores/
    │   │   ├── index.ts              # Pinia 配置
    │   │   ├── auth.ts               # 认证状态
    │   │   ├── chat.ts               # 聊天状态
    │   │   ├── channels.ts           # 通道状态
    │   │   ├── providers.ts          # Provider 状态
    │   │   ├── agent.ts              # Agent 状态
    │   │   └── settings.ts           # 设置状态
    │   ├── api/
    │   │   ├── index.ts              # Axios 实例配置
    │   │   ├── chat.ts               # Chat API
    │   │   ├── channels.ts           # Channels API
    │   │   ├── providers.ts          # Providers API
    │   │   ├── models.ts             # Models API
    │   │   ├── agent.ts              # Agent Config API
    │   │   ├── sessions.ts           # Sessions API
    │   │   ├── cron.ts               # Cron API
    │   │   ├── skills.ts             # Skills API
    │   │   ├── mcp.ts                # MCP API
    │   │   ├── files.ts              # Files API
    │   │   ├── stats.ts              # Token Stats API
    │   │   └── auth.ts               # Auth API
    │   ├── types/
    │   │   └── index.ts              # TypeScript 类型定义
    │   ├── utils/
    │   │   ├── format.ts             # 格式化函数
    │   │   └── storage.ts            # LocalStorage 封装
    │   └── styles/
    │       └── main.css              # 全局样式（Tailwind 配置）
    ├── public/
    ├── index.html
    ├── vite.config.ts
    ├── tailwind.config.js
    ├── tsconfig.json
    └── package.json
```

---

## 3. 路由设计

### 3.1 路由表

```typescript
const routes: RouteRecordRaw[] = [
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
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue')
      },
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('@/views/Chat.vue')
      },
      {
        path: 'channels',
        name: 'Channels',
        component: () => import('@/views/Channels.vue')
      },
      {
        path: 'providers',
        name: 'Providers',
        component: () => import('@/views/Providers.vue')
      },
      {
        path: 'models',
        name: 'Models',
        component: () => import('@/views/Models.vue')
      },
      {
        path: 'agent',
        name: 'AgentConfig',
        component: () => import('@/views/AgentConfig.vue')
      },
      {
        path: 'sessions',
        name: 'Sessions',
        component: () => import('@/views/Sessions.vue')
      },
      {
        path: 'cron',
        name: 'CronJobs',
        component: () => import('@/views/CronJobs.vue')
      },
      {
        path: 'skills',
        name: 'Skills',
        component: () => import('@/views/Skills.vue')
      },
      {
        path: 'mcp',
        name: 'MCPServers',
        component: () => import('@/views/MCPServers.vue')
      },
      {
        path: 'files',
        name: 'WorkspaceFiles',
        component: () => import('@/views/WorkspaceFiles.vue')
      },
      {
        path: 'stats',
        name: 'TokenStats',
        component: () => import('@/views/TokenStats.vue')
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/Settings.vue')
      }
    ]
  }
];
```

### 3.2 路由守卫

```typescript
// 简单模式：仅基础认证检查
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
```

---

## 4. API 服务层设计

### 4.1 Axios 实例配置

```typescript
// src/api/index.ts
import axios from 'axios';

const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// 请求拦截器 - 添加认证 Token
apiClient.interceptors.request.use(config => {
  const token = localStorage.getItem('auth_token');
  if (token && token !== 'auth-disabled') {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器 - 错误处理
apiClient.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('auth_token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default apiClient;
```

### 4.2 API 服务模块

每个模块对应一个 Web API 分组：

**Sessions API（新增）：**

```typescript
// src/api/sessions.ts
import apiClient from './index';

export interface SessionInfo {
  key: string;
  created: string;
  updated: string;
  message_count: number;
}

export interface SessionDetail extends SessionInfo {
  messages?: Message[];
}

export interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: string;
  toolCall?: any;
}

export const sessionsApi = {
  // 获取会话列表
  list(): Promise<SessionInfo[]> {
    return apiClient.get('/sessions').then(res => res.data);
  },

  // 获取会话详情
  getDetail(key: string): Promise<SessionDetail> {
    return apiClient.get(`/sessions/${key}`).then(res => res.data);
  },

  // 删除会话
  delete(key: string): Promise<void> {
    return apiClient.delete(`/sessions/${key}`).then(res => res.data);
  },

  // 注意：当前后端没有直接获取历史消息的 API
  // 如果需要，可以在后端新增 /api/sessions/{key}/messages 端点
  // 或者前端本地存储消息历史
  getMessages(key: string): Promise<Message[]> {
    // 方案 1: 调用后端 API（需要新增端点）
    // return apiClient.get(`/sessions/${key}/messages`).then(res => res.data.messages);

    // 方案 2: 从 localStorage 读取（简单模式）
    const stored = localStorage.getItem(`session:${key}:messages`);
    return Promise.resolve(stored ? JSON.parse(stored) : []);
  },

  // 保存消息到本地存储（简单模式）
  saveMessages(key: string, messages: Message[]): void {
    localStorage.setItem(`session:${key}:messages`, JSON.stringify(messages));
  }
};
```

```typescript
// src/api/chat.ts
import apiClient from './index';

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
    return apiClient.post('/chat', { message, sessionKey });
  },

  sendStream(message: string, sessionKey?: string): Promise<Response> {
    return fetch('/api/execute/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, sessionKey })
    });
  }
};

// 其他模块类似...
```

---

## 5. 状态管理设计 (Pinia)

### 5.1 Auth Store

```typescript
// src/stores/auth.ts
import { defineStore } from 'pinia';

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
      const response = await apiClient.post('/auth/login', { username, password });
      if (response.data.success) {
        this.token = response.data.token;
        this.isAuthenticated = true;
        localStorage.setItem('auth_token', response.data.token);
      }
    },

    logout() {
      this.token = null;
      this.isAuthenticated = false;
      localStorage.removeItem('auth_token');
    },

    checkAuth() {
      // 检查认证状态
    }
  }
});
```

### 5.2 Chat Store

```typescript
// src/stores/chat.ts
import { defineStore } from 'pinia';

// 消息类型
interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: string;
  toolCall?: ToolCall;
}

// 工具调用类型
interface ToolCall {
  toolId: string;
  toolName: string;
  status: 'running' | 'success' | 'error';
  duration: number;
  result: any;
  parameters: string;
  _expanded: boolean;
}

// 会话摘要（用于列表显示）
interface SessionSummary {
  key: string;
  title: string;
  preview: string;
  lastUpdated: string;
  messageCount: number;
  unread?: boolean;
}

// 会话详情
interface SessionDetail extends SessionSummary {
  messages: Message[];
}

// Chat State
interface ChatState {
  // 当前会话
  currentSession: SessionSummary | null;
  currentSessionKey: string | null;

  // 历史会话列表
  sessions: SessionSummary[];

  // 当前会话的消息
  messages: Message[];

  // 流式输出状态
  isStreaming: boolean;
  isConnected: boolean;
  currentStreamingMessageId: string | null;

  // UI 状态
  isSidebarOpen: boolean;
  isLoadingSessions: boolean;
  isLoadingMessages: boolean;
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => ({
    currentSession: null,
    currentSessionKey: null,
    sessions: [],
    messages: [],
    isStreaming: false,
    isConnected: false,
    currentStreamingMessageId: null,
    isSidebarOpen: true,
    isLoadingSessions: false,
    isLoadingMessages: false
  }),

  getters: {
    // 获取会话标题
    sessionTitle: (state) => (key: string) => {
      const session = state.sessions.find(s => s.key === key);
      return session?.title || '未命名会话';
    },

    // 获取会话预览
    sessionPreview: (state) => (key: string) => {
      const session = state.sessions.find(s => s.key === key);
      return session?.preview || '暂无消息';
    }
  },

  actions: {
    // ========== 会话管理 ==========

    // 加载会话列表
    async loadSessions() {
      this.isLoadingSessions = true;
      try {
        const response = await apiClient.get('/sessions');
        this.sessions = response.data.map((s: any) => ({
          key: s.key,
          title: `会话 ${s.key.slice(-8)}`, // 或用第一条消息生成标题
          preview: s.messages?.[0]?.content || '暂无消息',
          lastUpdated: s.updated,
          messageCount: s.message_count,
          unread: false
        }));
      } catch (error) {
        useToast().error('加载会话列表失败');
        throw error;
      } finally {
        this.isLoadingSessions = false;
      }
    },

    // 创建新会话
    createNewSession() {
      const newKey = 'web:' + Date.now();
      this.currentSessionKey = newKey;
      this.messages = [];
      this.currentSession = null;
    },

    // 切换到指定会话
    async selectSession(key: string) {
      this.currentSessionKey = key;
      await this.loadSessionMessages(key);
    },

    // ========== 消息管理 ==========

    // 加载会话消息
    async loadSessionMessages(key: string) {
      this.isLoadingMessages = true;
      try {
        // 注意：当前后端没有直接获取历史消息的 API
        // 方案 1: 新增 /api/sessions/{key}/messages 端点
        // 方案 2: 前端本地存储消息历史（推荐简单模式）

        // 这里假设后端会返回消息历史
        const response = await apiClient.get(`/sessions/${key}`);
        this.messages = response.data.messages || [];
        this.currentSession = {
          key: response.data.key,
          title: `会话 ${response.data.key.slice(-8)}`,
          preview: this.messages[0]?.content || '',
          lastUpdated: response.data.updated,
          messageCount: this.messages.length
        };
      } catch (error) {
        useToast().error('加载消息失败');
        throw error;
      } finally {
        this.isLoadingMessages = false;
      }
    },

    // 添加消息
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

    // 追加流式内容到最后一条 assistant 消息
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

    // 更新工具调用状态
    updateToolCall(toolId: string, updates: Partial<ToolCall>) {
      const toolMsg = this.messages.find(m =>
        m.toolCall && m.toolCall.toolId === toolId
      );
      if (toolMsg && toolMsg.toolCall) {
        Object.assign(toolMsg.toolCall, updates);
      }
    },

    // ========== SSE 流式输出处理 ==========

    handleStreamData(data: any, eventType: string | null) {
      // 处理流式数据（与现有逻辑一致）
      // ...
    },

    // ========== 删除会话 ==========

    async deleteSession(key: string) {
      try {
        await apiClient.delete(`/sessions/${key}`);
        this.sessions = this.sessions.filter(s => s.key !== key);
        if (this.currentSessionKey === key) {
          this.createNewSession();
        }
        useToast().success('会话已删除');
      } catch (error) {
        useToast().error('删除会话失败');
        throw error;
      }
    },

    // ========== UI 状态 ==========

    toggleSidebar() {
      this.isSidebarOpen = !this.isSidebarOpen;
    },

    setSessionTitle(key: string, title: string) {
      const session = this.sessions.find(s => s.key === key);
      if (session) {
        session.title = title;
      }
    }
  }
});
```

### 5.3 其他 Store

- `channels.ts` - 通道列表和状态
- `providers.ts` - Provider 配置
- `agent.ts` - Agent 配置和状态
- `settings.ts` - 系统设置

---

## 6. 核心组件设计

### 6.1 布局组件

**AppLayout.vue** - 主布局框架
- 包含顶部导航、侧边导航、主内容区
- 响应式设计，支持移动端
- 保持现有赛博朋克风格

**TopNav.vue** - 顶部导航栏
- Logo 和品牌名称
- 全局搜索框
- 通知图标
- 设置图标
- 用户头像

**SideNav.vue** - 侧边导航栏
- 系统状态指示器
- 导航菜单（Dashboard, Chat, Channels 等）
- 快速操作按钮（Deploy Agent）
- 辅助链接（Support, Terminal）

### 6.2 Chat 模块组件

**ChatWindow.vue** - 聊天窗口容器
- 消息列表区域
- 输入区域
- 模式切换（普通/流式）

**MessageList.vue** - 消息列表
- 用户消息
- Assistant 消息（含工具调用面板）
- 系统消息
- Trace 事件

**MessageInput.vue** - 输入框
- 命令行风格输入
- 附件按钮
- 表情按钮
- 发送按钮

**ToolCallPanel.vue** - 工具调用面板
- 折叠/展开头部
- 状态指示器
- 调用参数显示
- 结果展示（表格/JSON/文本）
- 复制按钮

### 6.3 通用组件

**Card.vue** - 卡片容器
**Button.vue** - 按钮（支持多种变体）
**Input.vue** - 输入框
**Modal.vue** - 模态框
**Toast.vue** - 提示消息

---

## 7. 页面设计

### 7.1 Dashboard.vue

**功能：** 系统概览

**内容：**
- 系统状态卡片（在线/离线、工作空间、模型）
- Token 使用概览（今日/本周/本月）
- 活跃会话数
- 通道状态概览
- 最近活动日志

### 7.2 Chat.vue

**功能：** 聊天界面（从现有 index.html 迁移）

**布局结构：**
```
┌─────────────────────────────────────────────────────────────┐
│                      Chat Header                            │
│  (当前会话信息 | 模式切换 | 历史会话按钮 | 更多操作)            │
├────────────────┬────────────────────────────────────────────┤
│                │                                            │
│  历史会话列表   │            消息窗口                        │
│  (可折叠)       │                                            │
│                │  - 用户消息 (右侧)                          │
│  - 新建对话     │  - AI 回复 (左侧)                            │
│  - 会话 1       │  - 工具调用面板                            │
│  - 会话 2       │  - 系统消息                                │
│  - ...          │                                            │
│                │                                            │
│                ├────────────────────────────────────────────┤
│                │            输入区域                         │
│                │  (命令行风格 | 模式选择 | 发送按钮)          │
└────────────────┴────────────────────────────────────────────┘
```

**内容：**

**左侧：历史会话列表（可折叠）**
- 新建对话按钮
- 会话列表（按时间倒序）
  - 会话标题（自动生成或自定义）
  - 最后一条消息预览
  - 时间戳（相对时间：10 分钟前、1 小时前等）
  - 未读指示器（如果有新消息）
- 搜索/过滤框
- 加载更多（滚动加载）

**主区域：消息窗口**
- 系统时间戳锚点
- 消息列表
  - 用户消息（右侧对齐，紫色主题）
  - AI 回复（左侧对齐，默认主题）
    - 支持流式输出显示
    - 工具调用面板（折叠/展开）
  - 系统消息（居中显示）
  - Trace 事件（可选显示）
- 自动滚动到底部
- 滚动到顶部加载历史消息

**底部：输入区域**
- 命令行风格输入框
- 附件/上传按钮
- 模式切换（普通模式 / 流式输出 SSE）
- 发送按钮

**功能特性：**
- 切换历史会话（加载指定会话的消息）
- 继续历史对话（在已有会话中发送新消息）
- 删除历史会话
- 重命名会话
- 导出会话（JSON 格式）
- 搜索会话内消息

### 7.3 Channels.vue

**功能：** 通道管理

**内容：**
- 通道列表（表格或卡片）
- 每个通道的状态（启用/禁用）
- 编辑按钮（打开配置弹窗）
- 配置项：enabled, token, allowFrom

### 7.4 Providers.vue

**功能：** LLM Provider 配置

**内容：**
- Provider 列表（DashScope, OpenAI, Anthropic 等）
- API Key 配置（隐藏显示）
- API Base URL 配置
- 授权状态指示器
- 测试连接按钮

### 7.5 Models.vue

**功能：** 模型管理

**内容：**
- 模型列表
- 当前选中模型
- 模型切换
- Provider 关联显示

### 7.6 AgentConfig.vue

**功能：** Agent 配置

**内容：**
- 工作空间路径
- 模型选择
- maxTokens 配置
- temperature 配置
- maxToolIterations 配置
- heartbeatEnabled 开关
- restrictToWorkspace 开关

### 7.7 Sessions.vue

**功能：** 会话管理

**内容：**
- 会话列表（表格形式）
- 每个会话的信息（key, created, updated, message_count）
- 搜索/过滤功能（按时间、会话 key）
- 查看会话详情（打开模态框或跳转详情页）
- 删除会话（批量删除支持）
- 导出会话（JSON 格式）

**会话详情页 / 组件：**

```typescript
// 会话详情数据结构
interface SessionDetail {
  key: string;
  created: string;
  updated: string;
  messages: {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
    timestamp: string;
    toolCall?: ToolCall;
  }[];
}
```

**会话详情视图功能：**
- 完整的历史对话记录（按时间排序）
- 消息时间戳显示
- 工具调用记录展开查看
- 继续在此会话中对话（可选）
- 复制消息内容
- 返回会话列表

---

### 7.7.1 SessionDetail.vue（新增）

**功能：** 查看历史会话的完整对话记录

**内容：**
- 会话基本信息头部（session key, 创建时间，最后更新时间）
- 消息列表（滚动查看）
  - 用户消息（右侧对齐，紫色主题）
  - AI 回复（左侧对齐，默认主题）
  - 工具调用面板（折叠/展开）
  - 系统消息（居中显示）
- 快捷操作栏
  - 继续对话（输入框，可在此会话继续发送消息）
  - 导出会话
  - 删除会话
  - 返回列表

**路由设计：**
```typescript
{
  path: 'sessions/:id',
  name: 'SessionDetail',
  component: () => import('@/views/SessionDetail.vue'),
  props: true
}
```

**API 调用：**
```typescript
// 获取会话列表
GET /api/sessions

// 获取会话详情（需要从后端新增端点，或前端根据 sessionKey 重建历史）
GET /api/sessions/{key}/messages

// 或者在 Chat Store 中本地存储历史消息
```

### 7.8 CronJobs.vue

**功能：** 定时任务管理

**内容：**
- 任务列表
- 创建任务表单
- 启用/禁用切换
- 删除任务
- 编辑任务（cron 表达式/周期性）

### 7.9 Skills.vue

**功能：** 技能管理

**内容：**
- 技能列表
- 技能内容编辑器
- 创建/更新技能
- 删除技能

### 7.10 MCPServers.vue

**功能：** MCP 服务器管理

**内容：**
- MCP 全局启用开关
- 服务器列表
- 添加服务器
- 服务器配置（类型、endpoint、API Key 等）
- 测试连接

### 7.11 WorkspaceFiles.vue

**功能：** 文件管理

**内容：**
- 文件浏览器（树形或列表）
- 文件内容预览/编辑
- 上传文件
- 保存文件

### 7.12 TokenStats.vue

**功能：** Token 使用统计

**内容：**
- 日期范围选择器
- 总用量卡片
- 按模型分组统计
- 按日期分组统计
- 图表展示

### 7.13 Settings.vue

**功能：** 系统设置

**内容：**
- Gateway 配置
- 认证配置（用户名/密码）
- 端口配置
- 其他系统设置

### 7.14 Login.vue

**功能：** 登录界面（简单模式）

**内容：**
- 用户名输入框
- 密码输入框
- 登录按钮
- 错误提示

---

## 8. 样式设计

### 8.1 Tailwind 配置

保留现有颜色系统和字体配置：

```javascript
// tailwind.config.js
module.exports = {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        // 保持现有颜色映射
        'surface-container-lowest': '#000000',
        'secondary': '#53ddfc',
        // ... 其他颜色
      },
      fontFamily: {
        headline: ['Space Grotesk'],
        body: ['Inter'],
        mono: ['JetBrains Mono']
      }
    }
  },
  plugins: []
}
```

### 8.2 全局样式

保留现有 CSS 变量和自定义样式：
- `.glass-panel` - 毛玻璃效果
- `.scanline-overlay` - 扫描线叠加
- `.terminal-cursor` - 终端光标动画
- 自定义滚动条

---

## 9. 构建和部署

### 9.1 开发模式

```bash
cd ui
npm install
npm run dev
```

### 9.2 生产构建

```bash
npm run build
```

构建产物输出到 `dist/` 目录。

### 9.3 部署到 Spring Boot

有两种方案：

**方案 A：复制静态文件**
```bash
# 构建后复制到 resources/static
cp -r dist/* ../src/main/resources/static/
```

**方案 B：Vite 配置输出到 resources/static**
```typescript
// vite.config.ts
export default {
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
}
```

**推荐方案 B** - 自动化程度更高。

---

## 10. 测试策略

### 10.1 单元测试（Vitest）

```typescript
// vitest.config.ts
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],
    coverage: {
      reporter: ['text', 'json', 'html']
    }
  }
});
```

**测试示例：**

```typescript
// src/stores/__tests__/auth.test.ts
import { setActivePinia, createPinia } from 'pinia';
import { useAuthStore } from '../auth';
import { describe, it, expect, beforeEach } from 'vitest';

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
  });

  it(' initializes with correct state', () => {
    const store = useAuthStore();
    expect(store.isAuthenticated).toBe(false);
    expect(store.token).toBeNull();
  });

  it('login updates state and localStorage', async () => {
    const store = useAuthStore();
    // Mock API response
    await store.login('testuser', 'password123');
    expect(store.isAuthenticated).toBe(true);
    expect(localStorage.getItem('auth_token')).toBeTruthy();
  });
});
```

### 10.2 组件测试

```typescript
// src/components/common/__tests__/Button.test.ts
import { mount } from '@vue/test-utils';
import Button from '../Button.vue';
import { describe, it, expect } from 'vitest';

describe('Button', () => {
  it('renders correctly', () => {
    const wrapper = mount(Button, {
      slots: { default: 'Click me' }
    });
    expect(wrapper.text()).toBe('Click me');
  });

  it('emits click event', async () => {
    const wrapper = mount(Button);
    await wrapper.trigger('click');
    expect(wrapper.emitted('click')).toHaveLength(1);
  });

  it('is disabled when disabled prop is true', async () => {
    const wrapper = mount(Button, {
      props: { disabled: true }
    });
    await wrapper.trigger('click');
    expect(wrapper.emitted('click')).toBeUndefined();
  });
});
```

### 10.3 E2E 测试（可选，Playwright）

```typescript
// e2e/login.spec.ts
import { test, expect } from '@playwright/test';

test('login flow', async ({ page }) => {
  await page.goto('/login');

  await page.fill('input[name="username"]', 'admin');
  await page.fill('input[name="password"]', 'password123');
  await page.click('button[type="submit"]');

  await expect(page).toHaveURL('/dashboard');
  await expect(page.locator('text=JobClaw System')).toBeVisible();
});
```

---

## 11. 迁移计划

### 阶段 1：项目搭建（Day 1）
- [ ] 创建 Vite + Vue 3 + TypeScript 项目
- [ ] 配置 Tailwind CSS（复制现有主题）
- [ ] 配置 Vue Router
- [ ] 配置 Pinia
- [ ] 配置 Axios API 客户端

### 阶段 2：核心组件（Day 2-3）
- [ ] 实现布局组件（AppLayout, TopNav, SideNav）
- [ ] 实现通用组件（Card, Button, Input, Modal, Toast）
- [ ] 实现 Chat 模块组件
- [ ] 实现 Login 页面

### 阶段 3：API 服务层（Day 4）
- [ ] 实现所有 API 服务模块
- [ ] 实现 Pinia stores
- [ ] 实现路由守卫

### 阶段 4：管理页面（Day 5-7）
- [ ] Dashboard 页面
- [ ] Channels 页面
- [ ] Providers 页面
- [ ] Models 页面
- [ ] AgentConfig 页面
- [ ] Sessions 页面
- [ ] CronJobs 页面
- [ ] Skills 页面
- [ ] MCPServers 页面
- [ ] WorkspaceFiles 页面
- [ ] TokenStats 页面
- [ ] Settings 页面

### 阶段 5：测试和优化（Day 8-9）
- [ ] 功能测试
- [ ] 样式调整
- [ ] 性能优化
- [ ] 错误处理完善

### 阶段 6：部署验证（Day 10）
- [ ] 构建配置验证
- [ ] Spring Boot 集成测试
- [ ] 文档更新

---

## 12. 风险和挑战

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 现有样式无法完全复现 | 中 | 保留原 index.html 作为参考，逐步调整 |
| API 变更导致适配问题 | 低 | API 服务层封装，便于调整 |
| SSE 流式输出兼容性问题 | 中 | 保留现有流式处理逻辑，逐步迁移 |
| 构建产物与 Spring Boot 集成问题 | 低 | 提前验证构建配置 |
| 多语言扩展需求 | 低 | 预留 i18n 接口，当前仅实现中文 |

---

## 13. 国际化 (i18n) 预留

虽然当前仅需中文支持，但为未来扩展预留接口：

```typescript
// src/i18n/index.ts
import { createI18n } from 'vue-i18n';

const messages = {
  zh-CN: {
    nav: {
      dashboard: '仪表盘',
      chat: '聊天',
      channels: '通道',
      providers: 'Provider',
      settings: '设置'
    },
    common: {
      save: '保存',
      cancel: '取消',
      delete: '删除',
      edit: '编辑',
      loading: '加载中...'
    }
  },
  en: {
    nav: {
      dashboard: 'Dashboard',
      chat: 'Chat',
      channels: 'Channels',
      providers: 'Providers',
      settings: 'Settings'
    },
    common: {
      save: 'Save',
      cancel: 'Cancel',
      delete: 'Delete',
      edit: 'Edit',
      loading: 'Loading...'
    }
  }
};

export const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'en',
  messages
});
```

---

## 14. CSS 架构

```
styles/
├── main.css              # Tailwind 指令和全局变量
├── base.css              # 基础样式重置
│   - CSS reset/normalize
│   -  CSS 变量定义
│   - 全局字体设置
├── components.css        # 通用组件样式
│   - Button, Input, Card 等样式
├── layout.css            # 布局相关样式
│   - AppLayout, TopNav, SideNav
├── utilities.css         # 工具类
│   - 动画、过渡、响应式工具类
└── themes/
    └── cyberpunk.css     # 赛博朋克主题（颜色、特效）
```

---

## 15. 成功标准

- [ ] 所有现有功能完整迁移
- [ ] 所有 Web API 有对应的前端管理界面
- [ ] 保持现有设计风格和用户体验
- [ ] 代码结构清晰，易于维护
- [ ] 构建产物可正确部署到 Spring Boot

---

## 附录

### A. 完整 API 端点清单

参考 `WebConsoleController.java` 中定义的所有端点。

| 类别 | 端点 | 方法 | 描述 |
|------|------|------|------|
| **核心** | `/api/status` | GET | 获取系统状态 |
| | `/api/chat` | POST | 发送消息（普通模式） |
| | `/api/chat/stream` | POST | 发送消息（流式输出） |
| | `/api/execute/stream` | POST | 执行任务并流式输出事件 |
| **会话** | `/api/sessions` | GET | 获取会话列表 |
| | `/api/sessions/{key}` | GET | 获取会话详情 |
| | `/api/sessions/{key}` | DELETE | 删除会话 |
| **认证** | `/api/auth/check` | GET | 检查认证状态 |
| | `/api/auth/login` | POST | 登录获取 Token |
| **通道** | `/api/channels` | GET | 列出所有通道 |
| | `/api/channels/{name}` | GET | 获取通道配置 |
| | `/api/channels/{name}` | PUT | 更新通道配置 |
| **Provider** | `/api/providers` | GET | 列出所有 Provider |
| | `/api/providers/{name}` | PUT | 更新 Provider 配置 |
| **模型** | `/api/models` | GET | 列出所有模型 |
| | `/api/config/model` | GET | 获取当前模型配置 |
| | `/api/config/model` | PUT | 更新模型配置 |
| **Agent** | `/api/config/agent` | GET | 获取 Agent 配置 |
| | `/api/config/agent` | PUT | 更新 Agent 配置 |
| **定时任务** | `/api/cron` | GET | 列出定时任务 |
| | `/api/cron` | POST | 创建定时任务 |
| | `/api/cron/{id}` | DELETE | 删除定时任务 |
| | `/api/cron/{id}/enable` | PUT | 启用/禁用任务 |
| **技能** | `/api/skills` | GET | 列出技能 |
| | `/api/skills/{name}` | GET | 获取技能内容 |
| | `/api/skills/{name}` | PUT | 创建/更新技能 |
| | `/api/skills/{name}` | DELETE | 删除技能 |
| **MCP** | `/api/mcp` | GET | 获取 MCP 配置 |
| | `/api/mcp` | PUT | 更新 MCP 全局配置 |
| | `/api/mcp` | POST | 添加 MCP 服务器 |
| | `/api/mcp/{name}` | PUT | 更新 MCP 服务器 |
| | `/api/mcp/{name}` | DELETE | 删除 MCP 服务器 |
| | `/api/mcp/{name}/test` | POST | 测试连接 |
| **文件** | `/api/workspace/files` | GET | 列出工作区文件 |
| | `/api/workspace/files/{name}` | GET | 读取文件 |
| | `/api/workspace/files/{name}` | PUT | 保存文件 |
| **Token 统计** | `/api/token-stats` | GET | 获取 Token 使用统计 |
| **反馈** | `/api/feedback` | GET | 获取反馈配置 |
| | `/api/feedback` | POST | 提交反馈 |
| **上传** | `/api/upload` | POST | 上传图片 |
| | `/api/files/{path}` | GET | 访问文件 |

### B. 现有颜色系统

```
surface-container-lowest: #000000
secondary: #53ddfc
error-dim: #d7383b
surface-container-low: #0c1326
surface-container-highest: #1c253e
// ... 完整颜色列表见 index.html tailwind.config
```

### C. 现有字体

- Headline: Space Grotesk
- Body: Inter
- Mono: JetBrains Mono
