import apiClient from './index';

export interface TokenStats {
  totalTokens: number;
  totalCost: number;
  byModel: Array<{ model: string; tokens: number; cost: number }>;
  byDate: Array<{ date: string; tokens: number }>;
}

export const statsApi = {
  get(startDate?: string, endDate?: string): Promise<TokenStats> {
    const params = new URLSearchParams();
    if (startDate) params.append('startDate', startDate);
    if (endDate) params.append('endDate', endDate);
    return apiClient.get(`/token-stats?${params}`).then(res => res.data);
  }
};
