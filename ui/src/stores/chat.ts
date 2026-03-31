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
  currentAssistantMessageId: string | null;
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
    currentAssistantMessageId: null,
    isSidebarOpen: true
  }),

  actions: {
    createNewSession() {
      const newKey = 'web:' + Date.now();
      this.currentSessionKey = newKey;
      this.messages = [];
    },

    addMessage(role: 'user' | 'assistant' | 'system', content: string, toolCall?: ToolCall) {
      const msg = {
        id: 'msg_' + Date.now(),
        role,
        content,
        timestamp: new Date().toISOString(),
        toolCall,
        kind: 'chat' as const
      };
      this.messages.push(msg);
    },

    upsertProgressMessage(payload: {
      content: string;
      runId?: string | null;
      boardId?: string | null;
      entryType?: string;
      latestEntryType?: string;
      latestEntryTitle?: string;
      totalEntries?: number;
      artifactCount?: number;
      riskCount?: number;
      summaryCount?: number;
    }) {
      const runId = payload.runId || undefined;
      const boardId = payload.boardId || undefined;
      const key = runId || boardId || 'session';
      const messageId = `progress_${key}`;

      const existing = this.messages.find(m => m.id === messageId);
      const nextMeta = {
        entryType: payload.entryType,
        latestEntryType: payload.latestEntryType,
        latestEntryTitle: payload.latestEntryTitle,
        totalEntries: payload.totalEntries,
        artifactCount: payload.artifactCount,
        riskCount: payload.riskCount,
        summaryCount: payload.summaryCount
      };

      if (existing) {
        existing.content = payload.content;
        existing.timestamp = new Date().toISOString();
        existing.role = 'system';
        existing.kind = 'progress';
        existing.runId = runId;
        existing.boardId = boardId;
        existing.progressMeta = nextMeta;
        return;
      }

      this.messages.push({
        id: messageId,
        role: 'system',
        content: payload.content,
        timestamp: new Date().toISOString(),
        kind: 'progress',
        runId,
        boardId,
        progressMeta: nextMeta
      });
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
    },

    // 开始流式会话，创建一个新的助手消息
    startStreamingSession() {
      const msg = {
        id: 'msg_' + Date.now(),
        role: 'assistant' as const,
        content: '',
        timestamp: new Date().toISOString(),
        toolCall: undefined
      };
      this.messages.push(msg);
      this.currentAssistantMessageId = msg.id;
      this.isStreaming = true;
      this.isConnected = true;
      return msg.id;
    },

    // 结束流式会话
    endStreamingSession() {
      this.isStreaming = false;
      this.isConnected = false;
      this.currentAssistantMessageId = null;
    },

    // 追加内容到当前流式消息
    appendToCurrentAssistantMessage(content: string) {
      if (this.currentAssistantMessageId) {
        const msg = this.messages.find(m => m.id === this.currentAssistantMessageId);
        if (msg) {
          msg.content += content;
          return;
        }
      }
      // 如果没有当前消息，回退到原来的逻辑
      this.appendToLastAssistantMessage(content);
    },

    // 在当前流式消息之前插入工具调用
    insertBeforeCurrentAssistantMessage(toolCall: ToolCall) {
      if (this.currentAssistantMessageId) {
        const currentIndex = this.messages.findIndex(m => m.id === this.currentAssistantMessageId);
        if (currentIndex !== -1) {
          const msg = {
            id: 'tool_' + Date.now(),
            role: 'assistant' as const,
            content: '',
            timestamp: new Date().toISOString(),
            toolCall
          };
          this.messages.splice(currentIndex, 0, msg);
          return msg;
        }
      }
      // 回退：添加到末尾
      return this.addMessage('assistant', '', toolCall);
    },

    // 获取当前流式消息
    getCurrentAssistantMessage() {
      if (this.currentAssistantMessageId) {
        return this.messages.find(m => m.id === this.currentAssistantMessageId);
      }
      return null;
    },

    // 获取最后一个运行中的工具消息
    getLastRunningToolMessage() {
      for (let i = this.messages.length - 1; i >= 0; i--) {
        const msg = this.messages[i];
        if (msg.toolCall && msg.toolCall.status === 'running') {
          return msg;
        }
      }
      return null;
    },

    // 清理残留的运行中工具消息
    cleanupRunningToolMessages() {
      for (let i = this.messages.length - 1; i >= 0; i--) {
        const msg = this.messages[i];
        if (msg.toolCall && msg.toolCall.status === 'running') {
          this.messages.splice(i, 1);
        }
      }
    }
  }
});
