import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type TransferRunView = Schemas['TransferRunView'];
export type TransferPreviewRequest = Schemas['TransferPreviewRequest'];
export type TransferExecuteRequest = Schemas['TransferExecuteRequest'];
export type TransferMode = TransferRunView['mode'];
export type TransferState = TransferRunView['state'];
export type Finding = Schemas['Finding'];
export type OrphanView = Schemas['OrphanView'];
export type OrphanReturnRequest = Schemas['OrphanReturnRequest'];
export type OrphanReturnView = Schemas['OrphanReturnView'];

/** Everything transfer sits under one key, so a `transfer` stream frame refreshes the run in view and the list. */
export const keys = {
  all: (clusterId: string) => clusterKey(clusterId, 'transfer'),
  runs: (clusterId: string) => clusterKey(clusterId, 'transfer', 'runs'),
  run: (clusterId: string, runId: string) => clusterKey(clusterId, 'transfer', 'runs', runId),
  orphans: (clusterId: string) => clusterKey(clusterId, 'transfer', 'orphans'),
};

const base = (clusterId: string) => `/clusters/${clusterId}/transfers`;

/** Freezes the selection and checks the target. Persists a PREVIEWED run; touches no broker. */
export function useTransferPreview(clusterId: string) {
  return useMutation<TransferRunView, ApiError, TransferPreviewRequest>({
    mutationFn: (body) =>
      request<TransferRunView>(`${base(clusterId)}/preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useTransferExecute(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<TransferRunView, ApiError, { runId: string; body: TransferExecuteRequest }>({
    mutationFn: ({ runId, body }) =>
      request<TransferRunView>(`${base(clusterId)}/runs/${runId}/execute`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all(clusterId) }),
  });
}

/** Stop, resume or return: each takes no body and answers with the run. */
export function useTransferCommand(clusterId: string, runId: string, command: 'stop' | 'resume' | 'return') {
  const qc = useQueryClient();
  return useMutation<TransferRunView, ApiError, void>({
    mutationFn: () => request<TransferRunView>(`${base(clusterId)}/runs/${runId}/${command}`, { method: 'POST' }),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.all(clusterId) }),
  });
}

/** Kept current by the `transfer` stream topic, which invalidates it per batch and per state change. */
export function useTransferRun(clusterId: string, runId: string): UseQueryResult<TransferRunView, ApiError> {
  return useQuery({
    queryKey: keys.run(clusterId, runId),
    queryFn: () => request<TransferRunView>(`${base(clusterId)}/runs/${runId}`),
  });
}

/** Runs with this cluster as the source or the target, newest first. */
export function useTransferRuns(clusterId: string): UseQueryResult<TransferRunView[], ApiError> {
  return useQuery({
    queryKey: keys.runs(clusterId),
    queryFn: () => request<TransferRunView[]>(`${base(clusterId)}/runs`),
  });
}

export function useOrphans(clusterId: string): UseQueryResult<OrphanView[], ApiError> {
  return useQuery({
    queryKey: keys.orphans(clusterId),
    queryFn: () => request<OrphanView[]>(`${base(clusterId)}/orphans`),
  });
}

export function useReturnOrphan(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<OrphanReturnView, ApiError, OrphanReturnRequest>({
    mutationFn: (body) =>
      request<OrphanReturnView>(`${base(clusterId)}/orphans/return`, { method: 'POST', body: JSON.stringify(body) }),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.orphans(clusterId) }),
  });
}
