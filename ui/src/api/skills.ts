import apiClient from './index';
import type { Skill } from '@/types';

export const skillsApi = {
  list(): Promise<Skill[]> {
    return apiClient.get('/skills').then(res => res.data);
  },

  get(name: string): Promise<Skill> {
    return apiClient.get(`/skills/${name}`).then(res => res.data);
  },

  save(name: string, content: string): Promise<void> {
    return apiClient.put(`/skills/${name}`, { content }).then(res => res.data);
  },

  delete(name: string): Promise<void> {
    return apiClient.delete(`/skills/${name}`).then(res => res.data);
  }
};
