import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { clusterKey, request, type ApiError } from './request.ts';

/** Generic paged envelope (`PagedView<T>` on the backend). */
export interface PagedView<T> {
  data: T[];
  page: number;
  pageSize: number;
  /** The total across all pages; null where the server cannot know it. */
  count: number | null;
  hasNext: boolean;
}

/** The largest page the server allows (`ResourceQuery.MAX_SIZE`). */
export const MAX_PAGE_SIZE = 500;

/**
 * Every row of a list the UI shows in full (roles, environments, channels, …): pages of the maximum size,
 * followed while the server says another page exists. Stopping early would read as "no such row".
 */
export async function requestAll<T>(path: string): Promise<T[]> {
  const joiner = path.includes('?') ? '&' : '?';
  const rows: T[] = [];
  for (let page = 1; ; page++) {
    const result = await request<PagedView<T>>(`${path}${joiner}size=${MAX_PAGE_SIZE}&page=${page}`);
    rows.push(...result.data);
    if (!result.hasNext) return rows;
  }
}

export interface ResourceParams {
  q?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function resourceSearch(params: ResourceParams): string {
  const sp = new URLSearchParams();
  if (params.q) sp.set('q', params.q);
  if (params.sort) sp.set('sort', params.sort);
  if (params.page && params.page > 1) sp.set('page', String(params.page));
  if (params.size) sp.set('size', String(params.size));
  const s = sp.toString();
  return s ? `?${s}` : '';
}

/** One page of a cluster-wide listing (queues, addresses, consumers, …), refetched on the usual interval. */
export function useResource<T>(
  id: string,
  kind: string,
  params: ResourceParams,
): UseQueryResult<PagedView<T>, ApiError> {
  return useQuery({
    queryKey: clusterKey(id, kind, params),
    queryFn: () => request<PagedView<T>>(`/clusters/${id}/${kind}${resourceSearch(params)}`),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}

/** Rows in the envelope every list endpoint answers with, for the fixtures that stand in for one. */
export function paged<T>(data: T[]): PagedView<T> {
  return { data, page: 1, pageSize: 50, count: data.length, hasNext: false };
}
