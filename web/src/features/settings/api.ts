import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type SettingsResponse = Schemas['SettingsResponse'];
export type SecretsStatus = Schemas['SecretsStatus'];
export type RotationView = Schemas['RotationView'];
export type StudioHealth = Schemas['StudioHealth'];
export type JobHealth = Schemas['JobHealth'];
export type NodeHealth = Schemas['NodeHealth'];
export type PoolHealth = Schemas['PoolHealth'];

const SETTINGS_KEY = ['settings'] as const;
const SECRETS_KEY = ['settings', 'secrets'] as const;
const HEALTH_KEY = ['system', 'health'] as const;

export function useSettings(): UseQueryResult<SettingsResponse, ApiError> {
  return useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => request<SettingsResponse>('/settings'),
  });
}

export function useUpdateSetting() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { key: string; value: string }>({
    mutationFn: ({ key, value }) =>
      request(`/settings/${encodeURIComponent(key)}`, {
        method: 'PUT',
        body: JSON.stringify({ value }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
}

export function useResetSetting() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (key) => request(`/settings/${encodeURIComponent(key)}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
}

/** Key provider, key versions and the last rotation; polled every 2 s while a rotation runs. */
export function useSecretsStatus(): UseQueryResult<SecretsStatus, ApiError> {
  return useQuery({
    queryKey: SECRETS_KEY,
    queryFn: () => request<SecretsStatus>('/settings/secrets'),
    refetchInterval: (query) => (query.state.data?.lastRotation?.status === 'RUNNING' ? 2_000 : false),
  });
}

/** Starts a key rotation (202); the status query then reports its progress. */
export function useStartRotation() {
  const qc = useQueryClient();
  return useMutation<RotationView, ApiError, void>({
    mutationFn: () => request<RotationView>('/settings/secrets/rotations', { method: 'POST' }),
    onSettled: () => qc.invalidateQueries({ queryKey: SECRETS_KEY }),
  });
}

/** Studio's own jobs, broker calls, pool and streams, refreshed every 5 s. */
export function useStudioHealth(): UseQueryResult<StudioHealth, ApiError> {
  return useQuery({
    queryKey: HEALTH_KEY,
    queryFn: () => request<StudioHealth>('/system/health'),
    refetchInterval: 5_000,
  });
}
