import { ref, onUnmounted, computed } from 'vue';
import { useChatStore } from '@/stores/chat';
import { useToast } from './useToast';

export function useChatStream() {
  const chatStore = useChatStore();
  const toast = useToast();
  const abortController = ref<AbortController | null>(null);
  const isConnecting = ref(false);

  const startStream = async (message: string) => {
    // 清理上一轮流式输出残留的运行中工具消息
    chatStore.cleanupRunningToolMessages();
    
    // 结束之前的流式会话（如果还在进行中）
    if (chatStore.isStreaming) {
      chatStore.endStreamingSession();
    }

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
      // 开始新的流式会话
      chatStore.startStreamingSession();

      if (!response.body) {
        throw new Error('Response body is null');
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      let currentEvent: string | null = null;
      let dataBuffer: string[] = [];  // 累积多行 data，跨 chunk 保持状态

      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          // 处理最后可能剩余的数据
          if (dataBuffer.length > 0) {
            const dataStr = dataBuffer.join('\n');
            try {
              const data = JSON.parse(dataStr);
              handleStreamData(data, currentEvent);
            } catch (e) {
              console.error('JSON parse error on done:', e, dataStr);
            }
          }
          chatStore.isStreaming = false;
          chatStore.isConnected = false;
          break;
        }

        // stream: true 正确处理跨 chunk 的多字节字符（如 emoji）
        const chunk = decoder.decode(value, { stream: true });
        buffer += chunk;

        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.trim() || line.trim().startsWith(':')) {
            // 空行或注释行，表示事件结束，处理累积的 data
            if (dataBuffer.length > 0) {
              const dataStr = dataBuffer.join('\n');
              dataBuffer = [];
              try {
                const data = JSON.parse(dataStr);
                handleStreamData(data, currentEvent);
              } catch (e) {
                console.error('JSON parse error:', e, dataStr);
              }
              currentEvent = null;
            }
            continue;
          }

          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
            continue;
          }

          if (line.startsWith('data:')) {
            // 累积 data 行（SSE 规范：连续的 data 行用换行符连接）
            dataBuffer.push(line.slice(5));
            continue;
          }
        }
      }
    } catch (error) {
      isConnecting.value = false;
      chatStore.endStreamingSession();

      if ((error as Error).name === 'AbortError') {
        console.log('Stream aborted');
      } else {
        toast.error('流式输出错误：' + (error as Error).message);
      }
    }
  };

  const handleStreamData = (data: any, eventType: string | null) => {
    const normalizedEventType = eventType?.trim().toLowerCase() || null;
    const type = normalizedEventType === 'execution-event' || normalizedEventType === 'history-event'
      ? (data.type as string)
      : (eventType || (data.type as string));

    switch (type) {
      case 'connected':
      case 'subscribed':
        chatStore.isConnected = true;
        break;

      case 'THINK_START':
      case 'think_start':
        // 开始流式会话（如果还没有开始）
        if (!chatStore.currentAssistantMessageId) {
          chatStore.startStreamingSession();
        }
        break;

      case 'THINK_STREAM':
      case 'think_stream':
        if (data.content) {
          chatStore.appendToCurrentAssistantMessage(data.content);
        }
        break;

      case 'THINK_END':
      case 'think_end':
        // 思考结束，将思考工具标记为完成
        {
          const messages = chatStore.messages;
          for (let i = messages.length - 1; i >= 0; i--) {
            const msg = messages[i];
            if (msg.toolCall && msg.toolCall.toolId === 'think' && msg.toolCall.status === 'running') {
              msg.toolCall.status = 'success';
              msg.toolCall.duration = data.metadata?.duration || 0;
              break;
            }
          }
        }
        break;

      case 'TOOL_START':
      case 'tool_start': {
        const toolName = data.metadata?.toolName || '工具';
        // 工具调用插入到当前流式消息之前
        chatStore.insertBeforeCurrentAssistantMessage({
          toolId: data.metadata?.toolId || 'tool_' + Date.now(),
          toolName: toolName,
          status: 'running',
          duration: 0,
          result: null,
          parameters: data.content || '',
          _expanded: false  // 默认收起，点击展开
        });
        break;
      }

      case 'TOOL_END':
      case 'tool_end':
        // 工具调用结束，状态在 TOOL_OUTPUT 时更新
        break;

      case 'TOOL_OUTPUT':
      case 'tool_output': {
        // 更新最后一个运行中工具的结果
        const messages = chatStore.messages;
        for (let i = messages.length - 1; i >= 0; i--) {
          const msg = messages[i];
          if (msg.toolCall && msg.toolCall.status === 'running') {
            msg.toolCall.status = 'success';
            msg.toolCall.result = data.content;
            msg.toolCall.duration = data.metadata?.duration || 0;
            break;
          }
        }
        break;
      }

      case 'TOOL_ERROR':
      case 'tool_error': {
        // 更新工具调用为错误状态
        const messages = chatStore.messages;
        for (let i = messages.length - 1; i >= 0; i--) {
          const msg = messages[i];
          if (msg.toolCall && msg.toolCall.status === 'running') {
            msg.toolCall.status = 'error';
            msg.toolCall.result = '错误：' + data.content;
            msg.toolCall.duration = data.metadata?.duration || 0;
            break;
          }
        }
        break;
      }

      case 'FINAL_RESPONSE':
      case 'final_response':
        // 结束流式会话
        chatStore.endStreamingSession();
        break;

      case 'ERROR':
      case 'error':
        toast.error(data.message || '发生错误');
        chatStore.endStreamingSession();
        break;

      case 'CUSTOM':
      case 'custom':
        // 自定义事件（如异步任务完成通知）
        // 显示为系统消息或 toast 通知
        if (data.metadata?.asyncTaskStatus === 'completed') {
          toast.success(data.content || '异步任务完成');
        } else if (data.metadata?.asyncTaskStatus === 'failed') {
          toast.error(data.content || '异步任务失败');
        } else {
          // 其他自定义消息，追加到当前消息
          chatStore.appendToCurrentAssistantMessage('\n\n' + data.content);
        }
        break;

      default:
        // 忽略未知事件类型
        break;
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
