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

// ── cross-node resource views (Phase 2) ────────────────────────────────────
//
// ADR-0019 chose OpenAPI-generated types with a recorded fallback: hand-written
// types plus a runtime boundary check on the new endpoints. That fallback is in
// effect — `springdoc` + `openapi-typescript` generation is filed as a
// fast-follow. `assertShape` below is the "fails loudly, not `undefined`" guard.

export interface PagedView<T> {
  data: T[];
  count: number;
  page: number;
  pageSize: number;
}

export interface QueueNodeCell {
  nodeId: string;
  nodeName: string;
  stale: boolean;
  lastSeenAt: string | null;
  messageCount: number;
  consumerCount: number;
  deliveringCount: number;
  scheduledCount: number;
}

export interface QueueView {
  address: string;
  queueName: string;
  routingType: string;
  durable: boolean;
  totalMessageCount: number;
  totalConsumerCount: number;
  totalDeliveringCount: number;
  totalScheduledCount: number;
  nodesPresent: number;
  nodesTotal: number;
  perNode: QueueNodeCell[];
}

export interface AddressView {
  nodeId: string;
  nodeName: string;
  name: string;
  routingTypes: string | null;
  queueCount: number;
  messageCount: number;
}

export interface ConsumerView {
  nodeId: string;
  nodeName: string;
  consumerId: string | null;
  sessionId: string | null;
  queueName: string | null;
  address: string | null;
  protocol: string | null;
  messagesDelivered: number;
  messagesAcknowledged: number;
  status: string | null;
}

export interface SessionView {
  nodeId: string;
  nodeName: string;
  sessionId: string | null;
  user: string | null;
  connectionId: string | null;
  consumerCount: number;
  producerCount: number;
  creationTime: string | null;
}

export interface ConnectionView {
  nodeId: string;
  nodeName: string;
  connectionId: string | null;
  remoteAddress: string | null;
  protocol: string | null;
  clientId: string | null;
  sessionCount: number;
  creationTime: string | null;
}

export interface ProducerView {
  nodeId: string;
  nodeName: string;
  producerId: string | null;
  name: string | null;
  sessionId: string | null;
  address: string | null;
  protocol: string | null;
  messagesSent: number;
}

/**
 * The runtime half of the ADR-0019 fallback: assert a decoded response has the
 * keys the type promises, so a backend contract drift fails here with a clear
 * message instead of rendering `undefined` three components deep.
 */
export function assertShape<T>(value: unknown, keys: (keyof T)[], name: string): T {
  if (value === null || typeof value !== 'object') {
    throw new Error(`Expected ${name} object, got ${value === null ? 'null' : typeof value}`);
  }
  const record = value as Record<string, unknown>;
  for (const key of keys) {
    if (!(String(key) in record)) {
      throw new Error(`${name} is missing "${String(key)}" — backend contract drift?`);
    }
  }
  return value as T;
}

function assertPaged<T>(body: unknown, name: string): PagedView<T> {
  const paged = assertShape<PagedView<T>>(
    body,
    ['data', 'count', 'page', 'pageSize'],
    name,
  );
  if (!Array.isArray(paged.data)) {
    throw new Error(`${name}.data is not an array — backend contract drift?`);
  }
  return paged;
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

export type ResourceKind =
  | 'queues'
  | 'addresses'
  | 'consumers'
  | 'sessions'
  | 'connections'
  | 'producers';

export interface ResourceParams {
  q?: string;
  sort?: string;
  page?: number;
  size?: number;
}

function resourceSearch(params: ResourceParams): string {
  const sp = new URLSearchParams();
  if (params.q) sp.set('q', params.q);
  if (params.sort) sp.set('sort', params.sort);
  if (params.page && params.page > 1) sp.set('page', String(params.page));
  if (params.size) sp.set('size', String(params.size));
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export const keys = {
  all: ['clusters'] as const,
  detail: (id: string) => ['clusters', id] as const,
  topology: (id: string) => ['clusters', id, 'topology'] as const,
  health: (id: string) => ['clusters', id, 'health'] as const,
  resource: (id: string, kind: ResourceKind, params: ResourceParams = {}) =>
    ['clusters', id, kind, params] as const,
  /** The TanStack Query key a stream topic invalidates. */
  topic: (id: string, topic: 'topology' | 'health' | 'queues') => {
    if (topic === 'topology') return ['clusters', id, 'topology'] as const;
    if (topic === 'health') return ['clusters', id, 'health'] as const;
    return ['clusters', id, 'queues'] as const;
  },
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

export function useTopology(
  id: string,
): UseQueryResult<TopologyView, ApiError> {
  return useQuery({
    queryKey: keys.topology(id),
    queryFn: () => request<TopologyView>(`/clusters/${id}/topology`),
    refetchInterval: 5_000,
  });
}

export function useHealth(id: string): UseQueryResult<HealthView, ApiError> {
  return useQuery({
    queryKey: keys.health(id),
    queryFn: () => request<HealthView>(`/clusters/${id}/health`),
    refetchInterval: 5_000,
  });
}

export function useQueues(
  id: string,
  params: ResourceParams = {},
): UseQueryResult<PagedView<QueueView>, ApiError> {
  return useQuery({
    queryKey: keys.resource(id, 'queues', params),
    queryFn: async () =>
      assertPaged<QueueView>(
        await request(`/clusters/${id}/queues${resourceSearch(params)}`),
        'PagedView<QueueView>',
      ),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}

function useResource<T>(
  id: string,
  kind: ResourceKind,
  params: ResourceParams,
  name: string,
): UseQueryResult<PagedView<T>, ApiError> {
  return useQuery({
    queryKey: keys.resource(id, kind, params),
    queryFn: async () =>
      assertPaged<T>(
        await request(`/clusters/${id}/${kind}${resourceSearch(params)}`),
        name,
      ),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}

export const useAddresses = (id: string, p: ResourceParams = {}) =>
  useResource<AddressView>(id, 'addresses', p, 'PagedView<AddressView>');
export const useConsumers = (id: string, p: ResourceParams = {}) =>
  useResource<ConsumerView>(id, 'consumers', p, 'PagedView<ConsumerView>');
export const useSessions = (id: string, p: ResourceParams = {}) =>
  useResource<SessionView>(id, 'sessions', p, 'PagedView<SessionView>');
export const useConnections = (id: string, p: ResourceParams = {}) =>
  useResource<ConnectionView>(id, 'connections', p, 'PagedView<ConnectionView>');
export const useProducers = (id: string, p: ResourceParams = {}) =>
  useResource<ProducerView>(id, 'producers', p, 'PagedView<ProducerView>');

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

// ── settings (Phase 2) ─────────────────────────────────────────────────────

export interface SettingValue {
  value: string;
  overridden: boolean;
  defaultValue: string;
}

export interface SettingsResponse {
  settings: Record<string, SettingValue>;
}

const SETTINGS_KEY = ['settings'] as const;

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
    mutationFn: (key) =>
      request(`/settings/${encodeURIComponent(key)}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
}

export function useRotateCredentials(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { username: string; password: string }>({
    mutationFn: (body) =>
      request(`/clusters/${clusterId}/credentials`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.detail(clusterId) }),
  });
}
