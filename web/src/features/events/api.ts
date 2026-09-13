import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, request } from "../../kernel/api/request.ts";
import { poll } from "../../kernel/api/polling.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type BrokerEventPageView = Schemas["BrokerEventPageView"];
export type BrokerEventView = Schemas["BrokerEventView"];

export interface EventFilter {
  type?: string;
  nodeId?: string;
  address?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export function useEvents(
  clusterId: string,
  filter: EventFilter = {},
): UseQueryResult<BrokerEventPageView, ApiError> {
  return useQuery({
    queryKey: ["clusters", clusterId, "events", filter],
    queryFn: () => {
      const sp = new URLSearchParams();
      for (const [k, v] of Object.entries(filter)) {
        if (v !== undefined && v !== "" && !(k === "page" && v === 1))
          sp.set(k, String(v));
      }
      const qs = sp.toString();
      return request<BrokerEventPageView>(
        `/clusters/${clusterId}/events${qs ? `?${qs}` : ""}`,
      );
    },
    refetchInterval: poll(5_000),
    placeholderData: (prev) => prev,
  });
}
