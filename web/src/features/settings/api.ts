import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, request } from "../../kernel/api/request.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type SettingsResponse = Schemas["SettingsResponse"];

const SETTINGS_KEY = ["settings"] as const;

export function useSettings(): UseQueryResult<SettingsResponse, ApiError> {
  return useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => request<SettingsResponse>("/settings"),
  });
}

export function useUpdateSetting() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { key: string; value: string }>({
    mutationFn: ({ key, value }) =>
      request(`/settings/${encodeURIComponent(key)}`, {
        method: "PUT",
        body: JSON.stringify({ value }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
}

export function useResetSetting() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (key) =>
      request(`/settings/${encodeURIComponent(key)}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
}
