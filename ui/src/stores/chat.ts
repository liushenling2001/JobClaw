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
