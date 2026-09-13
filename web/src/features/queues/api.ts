import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, clusterKey, lifecycleBase, lifecycleQuery, type LifecycleVars, request } from "../../kernel/api/request.ts";
import { type PagedView, type ResourceParams, resourceSearch } from "../../kernel/api/paging.ts";
import { poll } from "../../kernel/api/polling.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type CapabilityView = Schemas["CapabilityView"];
export type CreateAddressRequest = Schemas["CreateAddressRequest"];
export type CreateQueueRequest = Schemas["CreateQueueRequest"];
export type LifecycleOutcomeView = Schemas["LifecycleOutcomeView"];
export type QueueView = Schemas["QueueView"];
export type UpdateQueueRequest = Schemas["UpdateQueueRequest"];

export const keys = {
  resource: (id: string, kind: string, params: ResourceParams = {}) => clusterKey(id, kind, params),
  topic: (id: string, topic: string) => clusterKey(id, topic),
};

export function useQueues(
  id: string,
  params: ResourceParams = {},
): UseQueryResult<PagedView<QueueView>, ApiError> {
  return useQuery({
    queryKey: keys.resource(id, "queues", params),
    queryFn: () =>
      request<PagedView<QueueView>>(
        `/clusters/${id}/queues${resourceSearch(params)}`,
      ),
    // The command palette mounts on every route, including the ones outside a
    // cluster, where it has no id to give. Without this it requested
    // `/clusters//queues`, which is a 400 — and an errored observed query puts
    // the whole shell into its offline state, so every cluster-less screen
    // claimed Studio had lost the brokers.
    enabled: id !== "",
    refetchInterval: poll(5_000),
    placeholderData: (prev) => prev,
  });
}

/**
 * Invalidate on a real run only. A preview mutated nothing, so refetching after
 * one would cost a broker round trip to learn what we already know.
 */
function useLifecycleMutation<V extends LifecycleVars>(
  clusterId: string,
  send: (vars: V) => Promise<LifecycleOutcomeView>,
) {
  const qc = useQueryClient();
  return useMutation<LifecycleOutcomeView, ApiError, V>({
    mutationFn: send,
    onSuccess: (result) => {
      if (result.dryRun) return;
      qc.invalidateQueries({ queryKey: keys.topic(clusterId, "queues") });
      qc.invalidateQueries({ queryKey: keys.resource(clusterId, "addresses") });
    },
  });
}

export function useCreateQueue(clusterId: string) {
  return useLifecycleMutation<LifecycleVars & { body: CreateQueueRequest }>(
    clusterId,
    ({ body, dryRun }) =>
      request(`${lifecycleBase(clusterId)}/queues${lifecycleQuery(dryRun)}`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
  );
}

export function useUpdateQueue(clusterId: string, queueName: string) {
  return useLifecycleMutation<LifecycleVars & { body: UpdateQueueRequest }>(
    clusterId,
    ({ body, dryRun }) =>
      request(
        `${lifecycleBase(clusterId)}/queues/${encodeURIComponent(queueName)}${lifecycleQuery(dryRun)}`,
        { method: "PATCH", body: JSON.stringify(body) },
      ),
  );
}

export function useDeleteQueue(clusterId: string, queueName: string) {
  return useLifecycleMutation<LifecycleVars>(
    clusterId,
    ({ dryRun, override }) =>
      request(
        `${lifecycleBase(clusterId)}/queues/${encodeURIComponent(queueName)}${lifecycleQuery(dryRun, override)}`,
        { method: "DELETE" },
      ),
  );
}

/** Pause and resume are one hook: the same permission, and the UI toggles between them. */
export function useSetQueuePaused(clusterId: string, queueName: string) {
  return useLifecycleMutation<LifecycleVars & { paused: boolean }>(
    clusterId,
    ({ paused, dryRun }) =>
      request(
        `${lifecycleBase(clusterId)}/queues/${encodeURIComponent(queueName)}/${paused ? "pause" : "resume"}${lifecycleQuery(dryRun)}`,
        { method: "POST" },
      ),
  );
}

export function useResetQueueCounter(clusterId: string, queueName: string) {
  return useLifecycleMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(
      `${lifecycleBase(clusterId)}/queues/${encodeURIComponent(queueName)}/reset-counter${lifecycleQuery(dryRun)}`,
      { method: "POST" },
    ),
  );
}

export function useCreateAddress(clusterId: string) {
  return useLifecycleMutation<LifecycleVars & { body: CreateAddressRequest }>(
    clusterId,
    ({ body, dryRun }) =>
      request(
        `${lifecycleBase(clusterId)}/addresses${lifecycleQuery(dryRun)}`,
        {
          method: "POST",
          body: JSON.stringify(body),
        },
      ),
  );
}

export function useDeleteAddress(clusterId: string, address: string) {
  return useLifecycleMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(
      `${lifecycleBase(clusterId)}/addresses/${encodeURIComponent(address)}${lifecycleQuery(dryRun)}`,
      { method: "DELETE" },
    ),
  );
}
