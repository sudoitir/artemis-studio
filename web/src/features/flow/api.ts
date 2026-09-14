import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query';

import { poll } from '../../kernel/api/polling.ts';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';
import { DEFAULT_LIMIT, type FlowSearch } from './flowSearch.ts';

type Schemas = components['schemas'];

export type FlowGraphView = Schemas['FlowGraphView'];
export type FlowNodeView = Schemas['FlowNodeView'];
export type FlowEdgeView = Schemas['FlowEdgeView'];
export type FlowBrokerNodeView = Schemas['FlowBrokerNodeView'];
export type FlowKpis = Schemas['FlowKpis'];
export type FlowTotals = Schemas['FlowTotals'];

/**
 * How often an open flow view re-reads. Each read also renews the cluster's observation lease
 * (ADR-0081), so this must stay well inside the server's lease — it is what keeps sampling on.
 */
export const FLOW_POLL_MS = 15_000;

export function flowQueryString(search: FlowSearch): string {
  const params = new URLSearchParams();
  if (search.focus) params.set('focus', search.focus);
  if (search.hops) params.set('hops', String(search.hops));
  params.set('rank', search.rank ?? 'IN');
  params.set('limit', String(search.limit ?? DEFAULT_LIMIT));
  params.set('groupBy', search.groupBy ?? 'CLIENT_ID');
  return params.toString();
}

export function useFlowGraph(clusterId: string, search: FlowSearch): UseQueryResult<FlowGraphView, ApiError> {
  const qs = flowQueryString(search);
  return useQuery({
    queryKey: ['clusters', clusterId, 'flow', qs],
    queryFn: () => request<FlowGraphView>(`/clusters/${clusterId}/flow?${qs}`),
    refetchInterval: poll(FLOW_POLL_MS),
    // Changing the ranking or grouping keeps the previous graph on screen until the new one arrives.
    placeholderData: keepPreviousData,
  });
}
