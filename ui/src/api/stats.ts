import apiClient from './index';

export interface TokenStats {
  totalTokens?: number;
  inputTokens?: number;
  outputTokens?: number;
  byModel?: Array<{ model: string; total?: number; inputTokens?: number; outputTokens?: number }>;
  byDate?: Array<{ date: string; total?: number; inputTokens?: number; outputTokens?: number }>;
}

export const statsApi = {
  get(params?: { startDate?: string; endDate?: string }): Promise<TokenStats> {
    const queryParams = new URLSearchParams();
    if (params?.startDate) queryParams.append('startDate', params.startDate);
    if (params?.endDate) queryParams.append('endDate', params.endDate);
    const url = queryParams.toString() ? `/token-stats?${queryParams}` : '/token-stats';
    return apiClient.get(url).then(res => res.data);
  }
};
