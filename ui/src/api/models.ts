import apiClient from './index';
import type { ModelDefinition } from '@/types';

export const modelsApi = {
  list(): Promise<ModelDefinition[]> {
    return apiClient.get('/models').then(res => res.data);
  },

  getCurrent(): Promise<{ model: string; provider: string }> {
    return apiClient.get('/config/model').then(res => res.data);
  },

  update(modelName: string): Promise<void> {
    return apiClient.put('/config/model', { model: modelName }).then(res => res.data);
  }
};
