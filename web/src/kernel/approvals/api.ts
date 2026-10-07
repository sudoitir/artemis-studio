import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError, heldOperationsKey, request } from '../api/request.ts';
import type { components } from '../api/schema.d.ts';

type Schemas = components['schemas'];

export type HeldOperationSummary = Schemas['HeldOperationSummaryView'];
export type HeldOperationDetail = Schemas['HeldOperationDetailView'];
export type HeldOperationPage = Schemas['HeldOperationPageView'];
export type HeldDisplayRow = Schemas['HeldDisplayRowView'];
export type HeldEvent = Schemas['HeldEventView'];
export type HeldDecision = Schemas['HeldDecisionRequest'];
export type HeldState = HeldOperationSummary['state'];
export type GateStatus = Schemas['GateStatusView'];

/** Whose requests a list shows: the caller's own, or those waiting for the caller's decision. */
export type HeldScope = 'MINE' | 'DECIDABLE';

/** Every key starts from {@link heldOperationsKey}, so the user stream's `held` signal refreshes all of them. */
export const heldKeys = {
  all: heldOperationsKey,
  list: (scope: HeldScope, limit: number) => [...heldOperationsKey, 'list', scope, limit] as const,
  detail: (id: string) => [...heldOperationsKey, 'detail', id] as const,
  gate: ['gate', 'status'] as const,
};

/** The value the server writes in place of a secret parameter. */
export const REDACTED = '[redacted]';

/** Whether a request has ended: nothing more happens to it, and nobody can act on it. */
export const isClosed = (state: HeldState) => !(state === 'HELD' || state === 'APPROVED' || state === 'EXECUTING');

function listPath(scope: HeldScope, limit: number, before?: string) {
  const params = new URLSearchParams({ scope, limit: String(limit) });
  // The decidable list is what still waits for a decision; a closed request needs nobody.
  if (scope === 'DECIDABLE') params.set('state', 'HELD');
  if (before) params.set('before', before);
  return `/held-operations?${params}`;
}

/** The caller's own requests, or the ones waiting for them, newest first, a page at a time by keyset. */
export function useHeldList(scope: HeldScope, limit = 25) {
  return useInfiniteQuery({
    queryKey: heldKeys.list(scope, limit),
    queryFn: ({ pageParam }) => request<HeldOperationPage>(listPath(scope, limit, pageParam)),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
  });
}

/** One request as its page shows it. A request the caller may not see answers 404, like one that does not exist. */
export function useHeldOperation(id: string) {
  return useQuery<HeldOperationDetail, ApiError>({
    queryKey: heldKeys.detail(id),
    queryFn: () => request<HeldOperationDetail>(`/held-operations/${encodeURIComponent(id)}`),
    retry: (count, error) => error.status !== 404 && error.status !== 403 && count < 2,
  });
}

/** Approves or rejects a request, bound to the parameters and version the decider saw. */
export function useDecide(id: string) {
  const qc = useQueryClient();
  return useMutation<HeldOperationDetail, ApiError, HeldDecision>({
    mutationFn: (body) =>
      request<HeldOperationDetail>(`/held-operations/${encodeURIComponent(id)}/decision`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (detail) => qc.setQueryData(heldKeys.detail(id), detail),
    onSettled: () => qc.invalidateQueries({ queryKey: heldKeys.all }),
  });
}

/** Withdraws the caller's own request. */
export function useCancelHeld() {
  const qc = useQueryClient();
  return useMutation<HeldOperationDetail, ApiError, string>({
    mutationFn: (id) =>
      request<HeldOperationDetail>(`/held-operations/${encodeURIComponent(id)}/cancel`, { method: 'POST' }),
    onSuccess: (detail) => qc.setQueryData(heldKeys.detail(detail.operation.id), detail),
    onSettled: () => qc.invalidateQueries({ queryKey: heldKeys.all }),
  });
}

/** How often the shell asks whether break-glass is on. It is a deployment setting, so it changes only on a restart. */
const GATE_POLL_MS = 5 * 60_000;

/** Whether an approval provider is installed, and whether break-glass bypasses it. */
export function useGateStatus() {
  return useQuery({
    queryKey: heldKeys.gate,
    queryFn: () => request<GateStatus>('/gate/status'),
    staleTime: GATE_POLL_MS,
    refetchInterval: GATE_POLL_MS,
  });
}

/** The problem slug of a refused call, such as `held-operation-changed`. */
export function problemSlug(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  return error.type.slice(error.type.lastIndexOf('/') + 1) || undefined;
}
