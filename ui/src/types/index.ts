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

// 通道类型
export interface Channel {
  name: string;
  enabled: boolean;
  token?: string;
  allowFrom?: string[];
}

// Provider 类型
export interface Provider {
  name: string;
  apiBase: string;
  apiKey: string;
  authorized: boolean;
}

// 模型类型
export interface ModelDefinition {
  name: string;
  provider: string;
  model: string;
  maxContextSize?: number;
  description?: string;
  authorized: boolean;
}

// Agent 配置类型
export interface AgentConfig {
  workspace: string;
  model: string;
  maxTokens: number;
  temperature: number;
  maxToolIterations: number;
  heartbeatEnabled: boolean;
  restrictToWorkspace: boolean;
}

// Cron 任务类型
export interface CronJob {
  id: string;
  name: string;
  message: string;
  enabled: boolean;
  schedule?: string;
  everySeconds?: number;
  nextRun?: number;
}

// 技能类型
export interface Skill {
  name: string;
  description: string;
  source: string;
  path: string;
  content?: string;
}

// MCP 服务器类型
export interface MCPServer {
  name: string;
  type: 'sse' | 'stdio';
  description: string;
  endpoint?: string;
  apiKey?: string;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  enabled: boolean;
  timeout: number;
}

// 文件类型
export interface FileInfo {
  name: string;
  exists: boolean;
  size: number;
  lastModified: number;
  content?: string;
}
