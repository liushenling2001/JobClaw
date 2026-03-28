import apiClient from './index';
import type { MCPServer } from '@/types';

export const mcpApi = {
  getConfig(): Promise<{ enabled: boolean; servers: MCPServer[] }> {
    return apiClient.get('/mcp').then(res => res.data);
  },

  updateEnabled(enabled: boolean): Promise<void> {
    return apiClient.put('/mcp', { enabled }).then(res => res.data);
  },

  add(data: Partial<MCPServer>): Promise<void> {
    return apiClient.post('/mcp', data).then(res => res.data);
  },

  updateServer(name: string, data: Partial<MCPServer>): Promise<void> {
    return apiClient.put(`/mcp/${name}`, data).then(res => res.data);
  },

  delete(name: string): Promise<void> {
    return apiClient.delete(`/mcp/${name}`).then(res => res.data);
  },

  test(name: string): Promise<{ connected: boolean; initialized?: boolean }> {
    return apiClient.post(`/mcp/${name}/test`).then(res => res.data);
  }
};
