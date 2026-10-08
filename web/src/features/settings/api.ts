import {
  keepPreviousData,
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type SettingsResponse = Schemas['SettingsResponse'];
export type Setting = Schemas['SettingValue'];
export type PendingChange = Schemas['PendingChange'];
export type SettingChange = Schemas['SettingChangeRequest'];
export type ChangePreview = Schemas['ChangePreview'];
export type SecretsStatus = Schemas['SecretsStatus'];
export type RotationView = Schemas['RotationView'];
export type StudioHealth = Schemas['StudioHealth'];
export type JobHealth = Schemas['JobHealth'];
export type NodeHealth = Schemas['NodeHealth'];
export type ReplicaHealth = Schemas['ReplicaHealth'];
export type PoolHealth = Schemas['PoolHealth'];

export const SETTINGS_KEY = ['settings'] as const;

/** The header a change set's reason for approval travels in. */
const REASON_HEADER = 'X-Studio-Approval-Reason';
const SECRETS_KEY = ['settings', 'secrets'] as const;
const HEALTH_KEY = ['system', 'health'] as const;

export function useSettings(): UseQueryResult<SettingsResponse, ApiError> {
  return useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => request<SettingsResponse>('/settings'),
  });
}

/** Applies a change set together or not at all; a held one throws `OperationHeldError`. Refreshes the settings either way. */
export function useApplyChanges() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { changes: SettingChange[]; reason?: string }>({
    mutationFn: ({ changes, reason }) =>
      request('/settings/changes', {
        method: 'POST',
        body: JSON.stringify({ changes }),
        // A header carries only Latin-1, so the reason travels URL-encoded and the server decodes it.
        headers: reason ? { [REASON_HEADER]: encodeURIComponent(reason) } : undefined,
      }),
    // Awaited, so the draft is cleared against the values the server now holds rather than the old ones.
    onSettled: () => qc.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
}

/** What applying `changes` would do now: run, be held for approval or be denied, and which values are invalid. */
export const previewQuery = (changes: SettingChange[]) =>
  queryOptions<ChangePreview, ApiError>({
    queryKey: [...SETTINGS_KEY, 'preview', changes],
    queryFn: () =>
      request<ChangePreview>('/settings/changes/preview', { method: 'POST', body: JSON.stringify({ changes }) }),
  });

/** The preview of the draft, kept while the next one loads; disabled while there is nothing to preview. */
export function useChangePreview(changes: SettingChange[]): UseQueryResult<ChangePreview, ApiError> {
  return useQuery({ ...previewQuery(changes), enabled: changes.length > 0, placeholderData: keepPreviousData });
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
