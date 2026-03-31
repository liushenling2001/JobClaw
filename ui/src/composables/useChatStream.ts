import { ref, onUnmounted, computed } from 'vue';
import { useChatStore } from '@/stores/chat';
import { useToast } from './useToast';

export function useChatStream() {
  const chatStore = useChatStore();
  const toast = useToast();
  const abortController = ref<AbortController | null>(null);
  const isConnecting = ref(false);

  const startStream = async (message: string) => {
    // 娓呯悊涓婁竴杞祦寮忚緭鍑烘畫鐣欑殑杩愯涓伐鍏锋秷鎭?
    chatStore.cleanupRunningToolMessages();
    
    // 缁撴潫涔嬪墠鐨勬祦寮忎細璇濓紙濡傛灉杩樺湪杩涜涓級
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
      // 寮€濮嬫柊鐨勬祦寮忎細璇?
      chatStore.startStreamingSession();

      if (!response.body) {
        throw new Error('Response body is null');
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      let currentEvent: string | null = null;
      let dataBuffer: string[] = [];  // 绱Н澶氳 data锛岃法 chunk 淇濇寔鐘舵€?

      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          // 澶勭悊鏈€鍚庡彲鑳藉墿浣欑殑鏁版嵁
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

        // stream: true 姝ｇ‘澶勭悊璺?chunk 鐨勫瀛楄妭瀛楃锛堝 emoji锛?
        const chunk = decoder.decode(value, { stream: true });
        buffer += chunk;

        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.trim() || line.trim().startsWith(':')) {
            // 绌鸿鎴栨敞閲婅锛岃〃绀轰簨浠剁粨鏉燂紝澶勭悊绱Н鐨?data
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
            // 绱Н data 琛岋紙SSE 瑙勮寖锛氳繛缁殑 data 琛岀敤鎹㈣绗﹁繛鎺ワ級
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
        toast.error('流式输出错误: ' + (error as Error).message);
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
        // 寮€濮嬫祦寮忎細璇濓紙濡傛灉杩樻病鏈夊紑濮嬶級
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
        // 鎬濊€冪粨鏉燂紝灏嗘€濊€冨伐鍏锋爣璁颁负瀹屾垚
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
        const toolName = data.metadata?.toolName || '宸ュ叿';
        // 宸ュ叿璋冪敤鎻掑叆鍒板綋鍓嶆祦寮忔秷鎭箣鍓?
        chatStore.insertBeforeCurrentAssistantMessage({
          toolId: data.metadata?.toolId || 'tool_' + Date.now(),
          toolName: toolName,
          status: 'running',
          duration: 0,
          result: null,
          parameters: data.content || '',
          _expanded: false  // 榛樿鏀惰捣锛岀偣鍑诲睍寮€
        });
        break;
      }

      case 'TOOL_END':
      case 'tool_end':
        // 宸ュ叿璋冪敤缁撴潫锛岀姸鎬佸湪 TOOL_OUTPUT 鏃舵洿鏂?
        break;

      case 'TOOL_OUTPUT':
      case 'tool_output': {
        // 鏇存柊鏈€鍚庝竴涓繍琛屼腑宸ュ叿鐨勭粨鏋?
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
        // 鏇存柊宸ュ叿璋冪敤涓洪敊璇姸鎬?
        const messages = chatStore.messages;
        for (let i = messages.length - 1; i >= 0; i--) {
          const msg = messages[i];
          if (msg.toolCall && msg.toolCall.status === 'running') {
            msg.toolCall.status = 'error';
            msg.toolCall.result = '错误: ' + data.content;
            msg.toolCall.duration = data.metadata?.duration || 0;
            break;
          }
        }
        break;
      }

      case 'FINAL_RESPONSE':
      case 'final_response':
        // 缁撴潫娴佸紡浼氳瘽
        chatStore.endStreamingSession();
        break;

      case 'ERROR':
      case 'error':
        toast.error(data.message || '鍙戠敓閿欒');
        chatStore.endStreamingSession();
        break;      case 'CUSTOM':
      case 'custom':
        // Collaboration progress events are shown as grouped progress cards.
        if (data.metadata?.boardId || data.runId) {
          chatStore.upsertProgressMessage({
            content: data.content || 'Collaboration progress updated',
            runId: data.runId,
            boardId: data.metadata?.boardId,
            entryType: data.metadata?.entryType,
            latestEntryType: data.metadata?.latestEntryType,
            latestEntryTitle: data.metadata?.latestEntryTitle,
            totalEntries: data.metadata?.boardTotalEntries,
            artifactCount: data.metadata?.boardArtifactCount,
            riskCount: data.metadata?.boardRiskCount,
            summaryCount: data.metadata?.boardSummaryCount
          });
        } else if (data.metadata?.asyncTaskStatus === 'completed') {
          toast.success(data.content || '寮傛浠诲姟瀹屾垚');
        } else if (data.metadata?.asyncTaskStatus === 'failed') {
          toast.error(data.content || '寮傛浠诲姟澶辫触');
        } else {
          // Other custom events fallback to message stream.
          chatStore.appendToCurrentAssistantMessage('\n\n' + data.content);
        }
        break;

      default:
        // 蹇界暐鏈煡浜嬩欢绫诲瀷
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

