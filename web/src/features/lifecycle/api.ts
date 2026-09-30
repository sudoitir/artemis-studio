import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type StoreView = Schemas['StoreView'];
export type StoresResponse = Schemas['StoresResponse'];
export type TableView = Schemas['TableView'];
export type HealthResponse = Schemas['HealthResponse'];
export type PreviewResponse = Schemas['PreviewResponse'];
export type UpdatePolicyRequest = Schemas['UpdatePolicyRequest'];

const STORES_KEY = ['data', 'stores'] as const;
const HEALTH_KEY = ['data', 'health'] as const;

export function useStores(): UseQueryResult<StoresResponse, ApiError> {
  return useQuery({ queryKey: STORES_KEY, queryFn: () => request<StoresResponse>('/data/stores') });
}

export function useStorageHealth(): UseQueryResult<HealthResponse, ApiError> {
  return useQuery({ queryKey: HEALTH_KEY, queryFn: () => request<HealthResponse>('/data/health') });
}

export function useUpdatePolicy() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { id: string; body: UpdatePolicyRequest }>({
    mutationFn: ({ id, body }) =>
      request(`/data/stores/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: STORES_KEY }),
  });
}

export function usePreview() {
  return useMutation<PreviewResponse, ApiError, { id: string; retention: string }>({
    mutationFn: ({ id, retention }) =>
      request<PreviewResponse>(`/data/stores/${encodeURIComponent(id)}/preview`, {
        method: 'POST',
        body: JSON.stringify({ retention }),
      }),
  });
}
