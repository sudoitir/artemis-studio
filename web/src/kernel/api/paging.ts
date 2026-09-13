import { useQuery, type UseQueryResult } from "@tanstack/react-query";

import { poll } from "./polling.ts";
import { clusterKey, request, type ApiError } from "./request.ts";

/** Generic paged envelope (`PagedView<T>` on the backend). */
export interface PagedView<T> {
  data: T[];
  count: number;
  page: number;
  pageSize: number;
}

export interface ResourceParams {
  q?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function resourceSearch(params: ResourceParams): string {
  const sp = new URLSearchParams();
  if (params.q) sp.set("q", params.q);
  if (params.sort) sp.set("sort", params.sort);
  if (params.page && params.page > 1) sp.set("page", String(params.page));
  if (params.size) sp.set("size", String(params.size));
  const s = sp.toString();
  return s ? `?${s}` : "";
}

/** One page of a cluster-wide listing (queues, addresses, consumers, …), refetched on the usual interval. */
export function useResource<T>(
  id: string,
  kind: string,
  params: ResourceParams,
): UseQueryResult<PagedView<T>, ApiError> {
  return useQuery({
    queryKey: clusterKey(id, kind, params),
    queryFn: () =>
      request<PagedView<T>>(`/clusters/${id}/${kind}${resourceSearch(params)}`),
    refetchInterval: poll(5_000),
    placeholderData: (prev) => prev,
  });
}
