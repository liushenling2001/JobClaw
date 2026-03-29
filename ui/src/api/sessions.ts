import apiClient from './index';
import type { SessionInfo, Message, ToolCall } from '@/types';

// 后端返回的消息格式
interface BackendMessage {
  role: string;
  content: string;
  images?: string[];
  toolCalls?: BackendToolCall[];
  toolCallId?: string;
}

interface BackendToolCall {
  id: string;
  name: string;
  arguments: string;
}

// 后端返回的会话格式
interface BackendSession {
  key: string;
  messages: BackendMessage[];
  summary?: string;
  created: string;
  updated: string;
}

export const sessionsApi = {
  list(): Promise<SessionInfo[]> {
    return apiClient.get('/sessions').then(res => res.data);
  },

  getDetail(key: string): Promise<BackendSession> {
    return apiClient.get(`/sessions/${key}`).then(res => res.data);
  },

  // 从后端获取会话消息并转换为前端格式
  async getMessages(key: string): Promise<Message[]> {
    try {
      const session = await this.getDetail(key);
      return this.convertMessages(session.messages || []);
    } catch (e) {
      console.error('Failed to load messages from backend:', e);
      return [];
    }
  },

  // 转换后端消息格式为前端格式
  convertMessages(backendMessages: BackendMessage[]): Message[] {
    return backendMessages
      .filter(msg => msg.role === 'user' || msg.role === 'assistant')
      .map((msg, index) => ({
        id: `msg_${index}_${Date.now()}`,
        role: msg.role as 'user' | 'assistant',
        content: msg.content || '',
        timestamp: new Date().toISOString(),
        toolCall: msg.toolCalls && msg.toolCalls.length > 0
          ? this.convertToolCall(msg.toolCalls[0])
          : undefined
      }));
  },

  // 转换工具调用格式
  convertToolCall(tc: BackendToolCall): ToolCall {
    return {
      toolId: tc.id,
      toolName: tc.name,
      status: 'success',
      duration: 0,
      result: null,
      parameters: tc.arguments,
      _expanded: false
    };
  },

  delete(key: string): Promise<void> {
    return apiClient.delete(`/sessions/${key}`).then(res => res.data);
  },

  // 本地存储消息（备用）
  saveMessages(key: string, messages: Message[]): void {
    localStorage.setItem(`session:${key}:messages`, JSON.stringify(messages));
  }
};
