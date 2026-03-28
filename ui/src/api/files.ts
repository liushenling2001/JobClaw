import apiClient from './index';
import type { FileInfo } from '@/types';

export const filesApi = {
  list(): Promise<FileInfo[]> {
    return apiClient.get('/workspace/files').then(res => res.data);
  },

  read(name: string): Promise<FileInfo> {
    return apiClient.get(`/workspace/files/${name}`).then(res => res.data);
  },

  save(name: string, content: string): Promise<void> {
    return apiClient.put(`/workspace/files/${name}`, { content }).then(res => res.data);
  }
};
