import { ref, onUnmounted, computed } from 'vue';
import { useChatStore } from '@/stores/chat';
import { useToast } from './useToast';

export function useChatStream() {
  const chatStore = useChatStore();
  const toast = useToast();
  const abortController = ref<AbortController | null>(null);
  const isConnecting = ref(false);

  const startStream = async (message: string) => {
    abortController.value = new AbortController();
    isConnecting.value = true;

    try {
      const response = await fetch('/api/execute/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('auth_token') || ''}`
        },
        body: JSON.stringify({ message, sessionKey: chatStore.currentSessionKey }),
        signal: abortController.value.signal
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      isConnecting.value = false;
      chatStore.isStreaming = true;
      chatStore.isConnected = true;

      if (!response.body) {
        throw new Error('Response body is null');
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          chatStore.isStreaming = false;
          chatStore.isConnected = false;
          break;
        }

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
              console.error('JSON parse error:', e, dataStr);
            }
            currentEvent = null;
          }
        }
      }
    } catch (error) {
      isConnecting.value = false;
      chatStore.isStreaming = false;
      chatStore.isConnected = false;

      if ((error as Error).name === 'AbortError') {
        console.log('Stream aborted');
      } else {
        toast.error('流式输出错误：' + (error as Error).message);
      }
    }
  };

  const handleStreamData = (data: any, eventType: string | null) => {
    const type = eventType || data.type;

    switch (type) {
      case 'THINK_START':
      case 'think_start':
        chatStore.addMessage('assistant', '', {
          toolId: data.toolId || 'think',
          toolName: '思考',
          status: 'running',
          duration: 0,
          result: null,
          parameters: data.parameters || '',
          _expanded: false
        });
        break;

      case 'THINK_STREAM':
      case 'think_stream':
        if (data.content) {
          chatStore.appendToLastAssistantMessage(data.content);
        }
        break;

      case 'THINK_END':
      case 'think_end':
        // 思考结束，更新状态
        break;

      case 'TOOL_CALL':
      case 'tool_call':
        chatStore.addMessage('assistant', '', {
          toolId: data.toolId,
          toolName: data.toolName,
          status: 'running',
          duration: 0,
          result: null,
          parameters: data.parameters || '',
          _expanded: true
        });
        break;

      case 'TOOL_RESULT':
      case 'tool_result':
        // 更新最后一个工具调用的结果
        const messages = chatStore.messages;
        for (let i = messages.length - 1; i >= 0; i--) {
          const msg = messages[i];
          if (msg.toolCall && msg.toolCall.toolId === data.toolId) {
            msg.toolCall.status = data.success ? 'success' : 'error';
            msg.toolCall.result = data.result;
            msg.toolCall.duration = data.duration || 0;
            break;
          }
        }
        break;

      case 'FINAL_RESPONSE':
      case 'final_response':
        if (data.content) {
          chatStore.addMessage('assistant', data.content);
        }
        chatStore.isStreaming = false;
        break;

      case 'ERROR':
      case 'error':
        toast.error(data.message || '发生错误');
        chatStore.isStreaming = false;
        break;

      default:
        console.log('Unknown event type:', type, data);
    }
  };

  const stopStream = () => {
    abortController.value?.abort();
    chatStore.isStreaming = false;
    chatStore.isConnected = false;
  };

  onUnmounted(() => {
    stopStream();
  });

  return {
    startStream,
    stopStream,
    isStreaming: computed(() => chatStore.isStreaming),
    isConnected: computed(() => chatStore.isConnected),
    isConnecting
  };
}
