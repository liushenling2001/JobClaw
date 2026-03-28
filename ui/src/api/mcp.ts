import apiClient from './index';
import type { MCPServer } from '@/types';

export const mcpApi = {
  getConfig(): Promise<{ enabled: boolean; servers: MCPServer[] }> {
    return apiClient.get('/mcp').then(res => res.data);
  },

  updateEnabled(enabled: boolean): Promise<void> {
    return apiClient.put('/mcp', { enabled }).then(res => res.data);
  },

  addServer(server: MCPServer): Promise<void> {
    return apiClient.post('/mcp', server).then(res => res.data);
  },

  updateServer(name: string, server: Partial<MCPServer>): Promise<void> {
    return apiClient.put(`/mcp/${name}`, server).then(res => res.data);
  },

  deleteServer(name: string): Promise<void> {
    return apiClient.delete(`/mcp/${name}`).then(res => res.data);
  },

  testServer(name: string): Promise<{ connected: boolean }> {
    return apiClient.post(`/mcp/${name}/test`).then(res => res.data);
  }
};
