import { defineStore } from 'pinia';
import { authApi } from '@/api/auth';

interface AuthState {
  isAuthenticated: boolean;
  token: string | null;
  authEnabled: boolean;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    isAuthenticated: localStorage.getItem('auth_token') !== null,
    token: localStorage.getItem('auth_token'),
    authEnabled: true
  }),

  actions: {
    async login(username: string, password: string) {
      const response = await authApi.login(username, password);
      if (response.success && response.token) {
        this.token = response.token;
        this.isAuthenticated = true;
        localStorage.setItem('auth_token', response.token);
      }
      return response;
    },

    logout() {
      this.token = null;
      this.isAuthenticated = false;
      localStorage.removeItem('auth_token');
    }
  }
});
