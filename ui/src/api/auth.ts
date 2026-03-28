import apiClient from './index';
import type { ApiResponse } from '@/types';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  success: boolean;
  token?: string;
  error?: string;
}

export const authApi = {
  login(username: string, password: string): Promise<LoginResponse> {
    return apiClient.post('/auth/login', { username, password }).then(res => res.data);
  },

  checkAuth(): Promise<ApiResponse> {
    return apiClient.get('/auth/check').then(res => res.data);
  }
};
