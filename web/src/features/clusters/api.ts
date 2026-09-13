import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, clusterKey, request } from "../../kernel/api/request.ts";
import { poll } from "../../kernel/api/polling.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type CapabilitiesView = Schemas["CapabilitiesView"];
export type CapabilityView = Schemas["CapabilityView"];
export type ClusterDetail = Schemas["ClusterDetail"];
export type ClusterSummary = Schemas["ClusterSummary"];
export type EnvironmentRequest = Schemas["EnvironmentRequest"];
export type EnvironmentView = Schemas["EnvironmentView"];
export type HealthView = Schemas["HealthView"];
export type LogicalNodeView = Schemas["LogicalNodeView"];
export type NodeEndpointView = Schemas["NodeEndpointView"];
export type RegisterClusterRequest = Schemas["RegisterClusterRequest"];
export type RegisterPreview = Schemas["RegisterPreview"];
export type TopologyView = Schemas["TopologyView"];

/** String enums the backend serialises as bare strings; narrowed here for the UI. */
export type CapabilityStatus = "AVAILABLE" | "UNAVAILABLE" | "UNKNOWN";
export type SplitBrain = "NONE" | "SUSPECTED" | "CRITICAL";
export type HealthLevel = "OK" | "DEGRADED" | "CRITICAL" | "UNKNOWN";

export const keys = {
  all: ['clusters'] as const,
  detail: (id: string) => clusterKey(id),
  environments: ['environments'] as const,
  health: (id: string) => clusterKey(id, 'health'),
  topology: (id: string) => clusterKey(id, 'topology'),
};

export function useClusters(): UseQueryResult<ClusterSummary[], ApiError> {
  return useQuery({
    queryKey: keys.all,
    queryFn: () => request<ClusterSummary[]>("/clusters"),
    refetchInterval: poll(5_000),
  });
}

export function useCluster(
  id: string | null,
): UseQueryResult<ClusterDetail, ApiError> {
  return useQuery({
    queryKey: id ? keys.detail(id) : ["clusters", "none"],
    queryFn: () => request<ClusterDetail>(`/clusters/${id}`),
    enabled: id !== null,
    refetchInterval: poll(5_000),
  });
}

export function useTopology(
  id: string,
): UseQueryResult<TopologyView, ApiError> {
  return useQuery({
    queryKey: keys.topology(id),
    queryFn: () => request<TopologyView>(`/clusters/${id}/topology`),
    refetchInterval: poll(5_000),
  });
}

export function useHealth(id: string): UseQueryResult<HealthView, ApiError> {
  return useQuery({
    queryKey: keys.health(id),
    queryFn: () => request<HealthView>(`/clusters/${id}/health`),
    refetchInterval: poll(5_000),
  });
}

export function useCheckConnection() {
  return useMutation<RegisterPreview, ApiError, RegisterClusterRequest>({
    mutationFn: (body) =>
      request("/clusters?dryRun=true", {
        method: "POST",
        body: JSON.stringify(body),
      }),
  });
}

export function useRegisterCluster() {
  const qc = useQueryClient();
  return useMutation<ClusterDetail, ApiError, RegisterClusterRequest>({
    mutationFn: (body) =>
      request("/clusters", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

export function useRediscover(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<TopologyView, ApiError, void>({
    mutationFn: () =>
      request(`/clusters/${clusterId}/rediscover`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.detail(clusterId) }),
  });
}

export function useOverrideNodeUrl(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<
    NodeEndpointView,
    ApiError,
    { nodeId: string; jolokiaUrl?: string; coreUrl?: string }
  >({
    mutationFn: ({ nodeId, jolokiaUrl, coreUrl }) =>
      request(`/clusters/${clusterId}/nodes/${nodeId}`, {
        method: "PATCH",
        body: JSON.stringify({ jolokiaUrl, coreUrl }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.detail(clusterId) }),
  });
}

export function useDeleteCluster() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (id) => request(`/clusters/${id}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

export function useRotateCredentials(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<
    void,
    ApiError,
    { username: string; password: string; kind?: "JOLOKIA_BASIC" | "CORE" }
  >({
    mutationFn: (body) =>
      request(`/clusters/${clusterId}/credentials`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.detail(clusterId) }),
  });
}

export function useEnvironments(): UseQueryResult<EnvironmentView[], ApiError> {
  return useQuery({
    queryKey: keys.environments,
    queryFn: () => request<EnvironmentView[]>("/environments"),
  });
}

export function useCreateEnvironment() {
  const qc = useQueryClient();
  return useMutation<EnvironmentView, ApiError, EnvironmentRequest>({
    mutationFn: (body) =>
      request<EnvironmentView>("/environments", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.environments }),
  });
}

export function useUpdateEnvironment() {
  const qc = useQueryClient();
  return useMutation<
    EnvironmentView,
    ApiError,
    { environmentId: string; body: EnvironmentRequest }
  >({
    mutationFn: ({ environmentId, body }) =>
      request<EnvironmentView>(`/environments/${environmentId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.environments }),
  });
}

export function useDeleteEnvironment() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (environmentId) =>
      request<void>(`/environments/${environmentId}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.environments }),
  });
}

export function useAssignClusterEnvironment(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string | null>({
    mutationFn: (environmentId) =>
      request<void>(`/clusters/${clusterId}/environment`, {
        method: "PUT",
        body: JSON.stringify({ environmentId }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}
