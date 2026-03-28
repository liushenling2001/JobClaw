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
