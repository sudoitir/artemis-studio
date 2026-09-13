import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError, clusterKey, lifecycleBase, lifecycleQuery, type LifecycleVars, request } from "../../kernel/api/request.ts";
import { type ResourceParams, useResource } from "../../kernel/api/paging.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type AddressView = Schemas["AddressView"];
export type CapabilityView = Schemas["CapabilityView"];
export type ConnectionCloseView = Schemas["ConnectionCloseView"];
export type ConnectionView = Schemas["ConnectionView"];
export type ConsumerView = Schemas["ConsumerView"];
export type ProducerView = Schemas["ProducerView"];
export type SessionView = Schemas["SessionView"];

/** The four closes `connection-control` exposes; the first three are node-scoped. */
export type ConnectionCloseKind =
  "connection" | "session" | "consumer" | "address-consumers";

export const keys = {
  topic: (id: string, topic: string) => clusterKey(id, topic),
};

export const useAddresses = (id: string, p: ResourceParams = {}) =>
  useResource<AddressView>(id, "addresses", p);

export const useConsumers = (id: string, p: ResourceParams = {}) =>
  useResource<ConsumerView>(id, "consumers", p);

export const useSessions = (id: string, p: ResourceParams = {}) =>
  useResource<SessionView>(id, "sessions", p);

export const useConnections = (id: string, p: ResourceParams = {}) =>
  useResource<ConnectionView>(id, "connections", p);

export const useProducers = (id: string, p: ResourceParams = {}) =>
  useResource<ProducerView>(id, "producers", p);

/**
 * Closing a connection, a session, the connection behind a consumer, or every
 * consumer connection on an address.
 *
 * <p>A by-id close names the **node** the identifier was issued by — the one
 * mutating call in Studio that is not cluster-wide, because a connection id
 * means nothing on another node. Only the address-scoped close names the
 * cluster.
 *
 * <p>A real run invalidates the three views a close changes at once. A preview
 * invalidates nothing: it mutated nothing, and refetching would cost a broker
 * round trip to learn what we already know.
 */
function useCloseMutation<V extends LifecycleVars>(
  clusterId: string,
  send: (vars: V) => Promise<ConnectionCloseView>,
) {
  const qc = useQueryClient();
  return useMutation<ConnectionCloseView, ApiError, V>({
    mutationFn: send,
    onSuccess: (result) => {
      if (result.outcome.dryRun) return;
      for (const topic of ["connections", "sessions", "consumers"] as const) {
        qc.invalidateQueries({ queryKey: keys.topic(clusterId, topic) });
      }
    },
  });
}

/** `connection` | `session` | `consumer`, all node-scoped by the id's issuing node. */
export function useCloseNodeTarget(
  clusterId: string,
  kind: Exclude<ConnectionCloseKind, "address-consumers">,
  nodeId: string,
  targetId: string,
) {
  return useCloseMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(
      `${lifecycleBase(clusterId)}/nodes/${nodeId}/${kind}s/${encodeURIComponent(targetId)}/close${lifecycleQuery(dryRun)}`,
      { method: "POST" },
    ),
  );
}

/** Every consumer connection bound to an address, on every live node. Capped. */
export function useCloseAddressConsumers(clusterId: string, address: string) {
  return useCloseMutation<LifecycleVars>(clusterId, ({ dryRun, override }) =>
    request(
      `${lifecycleBase(clusterId)}/addresses/${encodeURIComponent(address)}/consumers/close${lifecycleQuery(dryRun, override)}`,
      { method: "POST" },
    ),
  );
}
