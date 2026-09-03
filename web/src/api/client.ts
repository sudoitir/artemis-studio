/**
 * Typed access to the Phase 1 cluster API. A thin `fetch` wrapper that parses
 * RFC 9457 `ProblemDetail` into a typed error, plus TanStack Query hooks against
 * the `QueryClient` configured in `main.tsx` (its `staleTime: 5_000` matches the
 * backend refresh loop).
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

const BASE = '/api/v1';

export type CapabilityStatus = 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN';
export type SplitBrain = 'NONE' | 'SUSPECTED' | 'CRITICAL';
export type HealthLevel = 'OK' | 'DEGRADED' | 'CRITICAL' | 'UNKNOWN';

export interface CapabilityView {
  status: CapabilityStatus;
  reason: string;
  brokerXmlSnippet: string | null;
}

export interface CapabilitiesView {
  managementRead: CapabilityView;
  managementWrite: CapabilityView;
  notifications: CapabilityView;
  messageIo: CapabilityView;
}

export interface NodeEndpointView {
  id: string;
  name: string;
  artemisNodeId: string | null;
  jolokiaUrl: string | null;
  coreUrl: string | null;
  haRole: 'PRIMARY' | 'BACKUP' | 'STANDALONE';
  state: 'STARTED' | 'STOPPED' | 'UNKNOWN';
  active: boolean;
  replicaSync: boolean | null;
  version: string | null;
  lastError: string | null;
  lastSeenAt: string | null;
  discovered: boolean;
  manualOverride: boolean;
  manageable: boolean;
}

export interface LogicalNodeView {
  artemisNodeId: string | null;
  splitBrain: SplitBrain;
  replicationBehind: boolean;
  endpoints: NodeEndpointView[];
}

export interface TopologyView {
  clusterId: string;
  nodes: LogicalNodeView[];
}

export interface HealthView {
  clusterId: string;
  level: HealthLevel;
  liveEndpointNames: string[];
  splitBrain: SplitBrain;
  replicationBehind: boolean;
  notes: string[];
}

export interface ClusterSummary {
  id: string;
  name: string;
  description: string | null;
  health: HealthLevel;
  nodeCount: number;
  updatedAt: string;
}

export interface ClusterDetail {
  id: string;
  name: string;
  description: string | null;
  topology: TopologyView;
  capabilities: CapabilitiesView;
  health: HealthView;
}

export interface RegisterPreview {
  capabilities: CapabilitiesView;
  reachableSeeds: number;
  discoveredNodes: number;
  nodeNames: string[];
}

export interface RegisterClusterRequest {
  seedUrls: string[];
  name?: string;
  description?: string;
  credentials?: { username: string; password: string };
  tlsBundle?: string;
}

/** A parsed `application/problem+json` body. */
export class ApiError extends Error {
  readonly status: number;
  readonly type: string;
  readonly title: string;
  readonly brokerErrorKind?: string;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(status: number, body: Record<string, unknown>) {
    super(
      (body.detail as string) ??
        (body.title as string) ??
        `Request failed (${status})`,
    );
    this.name = 'ApiError';
    this.status = status;
    this.type = (body.type as string) ?? 'about:blank';
    this.title = (body.title as string) ?? 'Error';
    this.brokerErrorKind = body.brokerErrorKind as string | undefined;
    this.fieldErrors =
      (body.errors as { field: string; message: string }[] | undefined) ?? [];
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'content-type': 'application/json', ...init?.headers },
    ...init,
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const body = text ? JSON.parse(text) : {};
  if (!res.ok) throw new ApiError(res.status, body);
  return body as T;
}

// ── queries ────────────────────────────────────────────────────────────────

const keys = {
  all: ['clusters'] as const,
  detail: (id: string) => ['clusters', id] as const,
};

export function useClusters(): UseQueryResult<ClusterSummary[], ApiError> {
  return useQuery({
    queryKey: keys.all,
    queryFn: () => request<ClusterSummary[]>('/clusters'),
    refetchInterval: 5_000,
  });
}

export function useCluster(
  id: string | null,
): UseQueryResult<ClusterDetail, ApiError> {
  return useQuery({
    queryKey: id ? keys.detail(id) : ['clusters', 'none'],
    queryFn: () => request<ClusterDetail>(`/clusters/${id}`),
    enabled: id !== null,
    refetchInterval: 5_000,
  });
}

// ── mutations ──────────────────────────────────────────────────────────────

export function useCheckConnection() {
  return useMutation<RegisterPreview, ApiError, RegisterClusterRequest>({
    mutationFn: (body) =>
      request('/clusters?dryRun=true', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  });
}

export function useRegisterCluster() {
  const qc = useQueryClient();
  return useMutation<ClusterDetail, ApiError, RegisterClusterRequest>({
    mutationFn: (body) =>
      request('/clusters', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

export function useRediscover(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<TopologyView, ApiError, void>({
    mutationFn: () =>
      request(`/clusters/${clusterId}/rediscover`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.detail(clusterId) }),
  });
}

export function useOverrideNodeUrl(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<
    NodeEndpointView,
    ApiError,
    { nodeId: string; jolokiaUrl: string }
  >({
    mutationFn: ({ nodeId, jolokiaUrl }) =>
      request(`/clusters/${clusterId}/nodes/${nodeId}`, {
        method: 'PATCH',
        body: JSON.stringify({ jolokiaUrl }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.detail(clusterId) }),
  });
}

export function useDeleteCluster() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (id) => request(`/clusters/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}
