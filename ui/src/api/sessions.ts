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
