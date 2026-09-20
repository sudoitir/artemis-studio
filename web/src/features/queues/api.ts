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
export type QueueConfiguration = Schemas["QueueConfiguration"];
export type QueueView = Schemas["QueueView"];
export type UpdateQueueRequest = Schemas["UpdateQueueRequest"];

export const keys = {
  resource: (id: string, kind: string, params: ResourceParams = {}) => clusterKey(id, kind, params),
  topic: (id: string, topic: string) => clusterKey(id, topic),
  configuration: (id: string, queueName: string) => clusterKey(id, "queues", queueName, "configuration"),
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
 * What the queue is configured as on each node that has it. The broker's update
 * replaces the whole configuration, so the edit form reads this first: a form of
 * blank inputs cannot say what the queue runs, nor whether an update took.
 */
export function useQueueConfiguration(
  clusterId: string,
  queueName: string,
  enabled = true,
): UseQueryResult<QueueConfiguration, ApiError> {
  return useQuery({
    queryKey: keys.configuration(clusterId, queueName),
    queryFn: () =>
      request<QueueConfiguration>(
        `/clusters/${clusterId}/queues/${encodeURIComponent(queueName)}/configuration`,
      ),
    enabled: enabled && clusterId !== "" && queueName !== "",
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
      // The queue's own configuration is what the edit form shows; leaving it
      // cached is how an applied change reads as though it never happened. The
      // topic key above already covers it — `clusters/<id>/queues` is its prefix.
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

/**
 * Delete a queue. `disconnectConsumers` closes its consumers so a node with any can delete it;
 * without it such a node refuses (ADR-0084).
 */
export function useDeleteQueue(clusterId: string, queueName: string) {
  return useLifecycleMutation<LifecycleVars & { disconnectConsumers?: boolean }>(
    clusterId,
    ({ dryRun, override, disconnectConsumers }) => {
      const query = lifecycleQuery(dryRun, override);
      const flag = disconnectConsumers ? `${query ? "&" : "?"}disconnectConsumers=true` : "";
      return request(
        `${lifecycleBase(clusterId)}/queues/${encodeURIComponent(queueName)}${query}${flag}`,
        { method: "DELETE" },
      );
    },
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
