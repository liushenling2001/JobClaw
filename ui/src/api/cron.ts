import apiClient from './index';
import type { CronJob } from '@/types';

export const cronApi = {
  list(): Promise<CronJob[]> {
    return apiClient.get('/cron').then(res => res.data);
  },

  create(job: Omit<CronJob, 'id'>): Promise<{ id: string }> {
    return apiClient.post('/cron', job).then(res => res.data);
  },

  delete(id: string): Promise<void> {
    return apiClient.delete(`/cron/${id}`).then(res => res.data);
  },

  enable(id: string, enabled: boolean): Promise<void> {
    return apiClient.put(`/cron/${id}/enable`, { enabled }).then(res => res.data);
  }
};
