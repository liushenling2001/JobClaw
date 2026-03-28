import apiClient from './index';
import type { AgentConfig } from '@/types';

export const agentApi = {
  getConfig(): Promise<AgentConfig> {
    return apiClient.get('/config/agent').then(res => res.data);
  },

  updateConfig(config: Partial<AgentConfig>): Promise<void> {
    return apiClient.put('/config/agent', config).then(res => res.data);
  }
};
