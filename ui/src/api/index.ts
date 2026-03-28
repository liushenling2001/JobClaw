import axios from 'axios';
import type { AxiosError, InternalAxiosRequestConfig, AxiosResponse } from 'axios';

const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// 请求拦截器 - 添加认证 Token
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('auth_token');
    if (token && token !== 'auth-disabled') {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// 响应拦截器 - 错误处理
apiClient.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('auth_token');
      window.location.href = '/login';
    } else if (error.response?.status === 403) {
      console.error('没有权限执行此操作');
    } else if (error.response?.status && error.response.status >= 500) {
      console.error('服务器错误:', error.response?.status);
    }
    return Promise.reject(error);
  }
);

export default apiClient;
