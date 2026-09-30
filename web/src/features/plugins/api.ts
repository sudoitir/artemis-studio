import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type PluginsView = Schemas['PluginsView'];
export type PluginView = Schemas['PluginView'];
export type PluginInfoView = Schemas['PluginInfoView'];
export type PluginPlanView = Schemas['PluginPlanView'];
export type PluginUploadView = Schemas['PluginUploadView'];
export type PluginViolationView = Schemas['PluginViolationView'];
export type PluginPurgePlanView = Schemas['PluginPurgePlanView'];
export type PluginUpdateView = Schemas['PluginUpdateView'];
export type PluginInstallerView = Schemas['PluginInstallerView'];
export type PluginTrustView = Schemas['PluginTrustView'];
export type TrustedKeysView = Schemas['TrustedKeysView'];
export type TrustedKeyView = Schemas['TrustedKeyView'];
export type AddKeyRequest = Schemas['AddKeyRequest'];
export type StudioRestartView = Schemas['StudioRestartView'];
export type AuditEventView = Schemas['AuditEventView'];

const BASE = '/admin/plugins';

export const keys = {
  all: ['plugins'] as const,
  list: ['plugins', 'list'] as const,
  one: (id: string) => ['plugins', 'one', id] as const,
  history: (id: string) => ['plugins', 'history', id] as const,
  upload: (sha: string) => ['plugins', 'upload', sha] as const,
  installers: ['plugins', 'installers'] as const,
  trust: ['plugins', 'trust'] as const,
};

/** Every problem from these endpoints lists its reasons; this reads them back out. */
export function violationsOf(error: unknown): PluginViolationView[] {
  if (!(error instanceof ApiError)) return [];
  const listed = error.problem.violations;
  return Array.isArray(listed) ? (listed as PluginViolationView[]) : [];
}

/** Something is changing right now: watch it closely. */
function busy(view: PluginsView | undefined): boolean {
  return !!view && (view.restart.restarting || view.plugins.some((p) => p.status === 'activating'));
}

/**
 * The inventory. Polled every second while a plugin is activating or Studio is restarting, and
 * every 30 seconds otherwise (design.md §8: progress is polled, not streamed). While Studio
 * restarts, a failed poll is expected and simply retried.
 */
export function usePlugins(): UseQueryResult<PluginsView, ApiError> {
  return useQuery({
    queryKey: keys.list,
    queryFn: () => request<PluginsView>(BASE),
    refetchInterval: (query) => (busy(query.state.data) || query.state.error ? 1_000 : 30_000),
    retry: (count, error) => error.status !== 403 && count < 3,
  });
}

export function usePluginHistory(id: string | undefined): UseQueryResult<AuditEventView[], ApiError> {
  return useQuery({
    queryKey: keys.history(id ?? ''),
    queryFn: () => request<AuditEventView[]>(`${BASE}/${encodeURIComponent(id!)}/history`),
    enabled: !!id,
  });
}

export function usePurgePlan(id: string | undefined, enabled: boolean): UseQueryResult<PluginPurgePlanView, ApiError> {
  return useQuery({
    queryKey: [...keys.one(id ?? ''), 'purge-plan'],
    queryFn: () =>
      request<PluginPurgePlanView>(`${BASE}/${encodeURIComponent(id!)}/purge?dryRun=true`, { method: 'POST' }),
    enabled: !!id && enabled,
  });
}

export function useInstallers(enabled: boolean): UseQueryResult<PluginInstallerView[], ApiError> {
  return useQuery({
    queryKey: keys.installers,
    queryFn: () => request<PluginInstallerView[]>(`${BASE}/installers`),
    enabled,
  });
}

export function useTrustedKeys(enabled: boolean): UseQueryResult<TrustedKeysView, ApiError> {
  return useQuery({
    queryKey: keys.trust,
    queryFn: () => request<TrustedKeysView>(`${BASE}/keys`),
    enabled,
  });
}

/** What the installer is asked to confirm before a plan is activated; the server enforces it. */
const acknowledged = (acknowledge: boolean | undefined) => (acknowledge ? '?acknowledge=true' : '');

function useInvalidating<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation<TResult, ApiError, TVars>({
    mutationFn: fn,
    onSettled: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

/** Sends the jar as the raw request body; the server validates it before storing anything. */
export function useUpload() {
  return useInvalidating((file: File) =>
    request<PluginUploadView>(`${BASE}/upload`, {
      method: 'PUT',
      body: file,
      headers: { 'content-type': 'application/octet-stream' },
    }),
  );
}

export function useDownloadUpdate() {
  return useInvalidating((id: string) =>
    request<PluginUploadView>(`${BASE}/${encodeURIComponent(id)}/download-update`, { method: 'POST' }),
  );
}

export function useCheckUpdates() {
  return useMutation<PluginUpdateView[], ApiError, void>({
    mutationFn: () => request<PluginUpdateView[]>(`${BASE}/check-updates`, { method: 'POST' }),
  });
}

export function useDiscardUpload() {
  return useInvalidating((sha: string) => request<void>(`${BASE}/uploads/${sha}`, { method: 'DELETE' }));
}

export function useActivateUpload() {
  return useInvalidating(({ sha, acknowledge }: { sha: string; acknowledge: boolean }) =>
    request<PluginPlanView>(`${BASE}/uploads/${sha}/activate${acknowledged(acknowledge)}`, { method: 'POST' }),
  );
}

export type LifecycleAction = 'enable' | 'rollback' | 'disable' | 'uninstall';

/**
 * Enable, roll back, disable or uninstall; `cascade` also disables the plugins that require it, and
 * `acknowledge` confirms what an enable or rollback needs confirmed (the server says what).
 */
export function useLifecycle() {
  return useInvalidating(
    ({
      id,
      action,
      cascade,
      acknowledge,
    }: {
      id: string;
      action: LifecycleAction;
      cascade?: boolean;
      acknowledge?: boolean;
    }) =>
      request<PluginPlanView | undefined>(
        `${BASE}/${encodeURIComponent(id)}/${action}${cascade ? '?cascade=true' : acknowledged(acknowledge)}`,
        { method: 'POST' },
      ),
  );
}

export function usePurge() {
  return useInvalidating((id: string) =>
    request<PluginPurgePlanView>(`${BASE}/${encodeURIComponent(id)}/purge?dryRun=false`, { method: 'POST' }),
  );
}

export function useRestartStudio() {
  return useInvalidating(() => request<void>(`${BASE}/restart`, { method: 'POST' }));
}

export function useGrantInstaller() {
  return useInvalidating((username: string) =>
    request<void>(`${BASE}/installers`, { method: 'POST', body: JSON.stringify({ username }) }),
  );
}

export function useRevokeInstaller() {
  return useInvalidating((userId: string) => request<void>(`${BASE}/installers/${userId}`, { method: 'DELETE' }));
}

export function useAddKey() {
  return useInvalidating((body: AddKeyRequest) =>
    request<TrustedKeyView>(`${BASE}/keys`, { method: 'POST', body: JSON.stringify(body) }),
  );
}

export function useRemoveKey() {
  return useInvalidating((fingerprint: string) =>
    request<void>(`${BASE}/keys/${encodeURIComponent(fingerprint)}`, { method: 'DELETE' }),
  );
}

export function useTrustPolicy() {
  return useInvalidating((allowUnverified: boolean) =>
    request<void>(`${BASE}/trust-policy`, { method: 'PUT', body: JSON.stringify({ allowUnverified }) }),
  );
}
