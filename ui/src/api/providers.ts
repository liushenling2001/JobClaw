import apiClient from './index';
import type { Provider } from '@/types';

export const providersApi = {
  list(): Promise<Provider[]> {
    return apiClient.get('/providers').then(res => res.data);
  },

  get(name: string): Promise<Provider> {
    return apiClient.get(`/providers/${name}`).then(res => res.data);
  },

  update(name: string, provider: Partial<Provider>): Promise<void> {
    return apiClient.put(`/providers/${name}`, provider).then(res => res.data);
  }
};
