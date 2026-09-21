import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, request } from "../../kernel/api/request.ts";
import { poll } from "../../kernel/api/polling.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type AuditEventView = Schemas["AuditEventView"];
export type AuditPageView = Schemas["AuditPageView"];

export interface AuditFilter {
  user?: string;
  action?: string;
  outcome?: string;
  /** Only the events that belong to this one, such as the queues of a bulk run. */
  parentId?: number;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export function useAudit(
  clusterId: string,
  filter: AuditFilter = {},
): UseQueryResult<AuditPageView, ApiError> {
  return useQuery({
    queryKey: ["clusters", clusterId, "audit", filter],
    queryFn: () => {
      const sp = new URLSearchParams();
      for (const [k, v] of Object.entries(filter)) {
        if (v !== undefined && v !== "" && !(k === "page" && v === 1))
          sp.set(k, String(v));
      }
      const qs = sp.toString();
      return request<AuditPageView>(
        `/clusters/${clusterId}/audit${qs ? `?${qs}` : ""}`,
      );
    },
    refetchInterval: poll(5_000),
    placeholderData: (prev) => prev,
  });
}
