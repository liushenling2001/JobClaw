import apiClient from './index';
import type { Channel } from '@/types';

export const channelsApi = {
  list(): Promise<Channel[]> {
    return apiClient.get('/channels').then(res => res.data);
  },

  get(name: string): Promise<Channel> {
    return apiClient.get(`/channels/${name}`).then(res => res.data);
  },

  update(name: string, channel: Partial<Channel>): Promise<void> {
    return apiClient.put(`/channels/${name}`, channel).then(res => res.data);
  }
};
