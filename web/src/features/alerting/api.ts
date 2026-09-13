import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, clusterKey, request } from "../../kernel/api/request.ts";
import { poll } from "../../kernel/api/polling.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type AlertFiringPageView = Schemas["AlertFiringPageView"];
export type AlertFiringView = Schemas["AlertFiringView"];
export type AlertRuleRequest = Schemas["AlertRuleRequest"];
export type AlertRuleView = Schemas["AlertRuleView"];
export type ClusterFiringCountView = Schemas["ClusterFiringCountView"];
export type NotificationChannelRequest = Schemas["NotificationChannelRequest"];
export type NotificationChannelView = Schemas["NotificationChannelView"];

export const keys = {
  alertFiring: (id: string) => clusterKey(id, 'alerts', 'firing'),
  alertHistory: (id: string, page: number, size: number) => clusterKey(id, 'alerts', 'history', page, size),
  alertRules: (id: string) => clusterKey(id, 'alerts', 'rules'),
  channels: ['channels'] as const,
  firingCounts: ['alerts', 'firing'] as const,
};

export function useAlertRules(
  clusterId: string,
): UseQueryResult<AlertRuleView[], ApiError> {
  return useQuery({
    queryKey: keys.alertRules(clusterId),
    queryFn: () =>
      request<AlertRuleView[]>(`/clusters/${clusterId}/alerts/rules`),
  });
}

export function useCreateAlertRule(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<AlertRuleView, ApiError, AlertRuleRequest>({
    mutationFn: (body) =>
      request<AlertRuleView>(`/clusters/${clusterId}/alerts/rules`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: keys.alertRules(clusterId) }),
  });
}

export function useUpdateAlertRule(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<
    AlertRuleView,
    ApiError,
    { ruleId: string; body: AlertRuleRequest }
  >({
    mutationFn: ({ ruleId, body }) =>
      request<AlertRuleView>(`/clusters/${clusterId}/alerts/rules/${ruleId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: keys.alertRules(clusterId) }),
  });
}

export function useDeleteAlertRule(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (ruleId) =>
      request<void>(`/clusters/${clusterId}/alerts/rules/${ruleId}`, {
        method: "DELETE",
      }),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: keys.alertRules(clusterId) }),
  });
}

export function useFiringAlerts(
  clusterId: string,
): UseQueryResult<AlertFiringView[], ApiError> {
  return useQuery({
    queryKey: keys.alertFiring(clusterId),
    queryFn: () =>
      request<AlertFiringView[]>(`/clusters/${clusterId}/alerts/firing`),
    refetchInterval: poll(15_000),
  });
}

export function useAlertHistory(
  clusterId: string,
  page: number,
  size: number,
): UseQueryResult<AlertFiringPageView, ApiError> {
  return useQuery({
    queryKey: keys.alertHistory(clusterId, page, size),
    queryFn: () =>
      request<AlertFiringPageView>(
        `/clusters/${clusterId}/alerts/history?page=${page}&size=${size}`,
      ),
    placeholderData: (prev) => prev,
  });
}

/** Cross-cluster open-firing counts for the shell badge — polled, since the SSE stream is per-cluster. */
export function useFiringCounts(
  enabled = true,
): UseQueryResult<ClusterFiringCountView[], ApiError> {
  return useQuery({
    queryKey: keys.firingCounts,
    queryFn: () => request<ClusterFiringCountView[]>("/alerts/firing"),
    refetchInterval: poll(30_000),
    enabled,
  });
}

export function useNotificationChannels(): UseQueryResult<
  NotificationChannelView[],
  ApiError
> {
  return useQuery({
    queryKey: keys.channels,
    queryFn: () => request<NotificationChannelView[]>("/channels"),
  });
}

export function useCreateNotificationChannel() {
  const qc = useQueryClient();
  return useMutation<
    NotificationChannelView,
    ApiError,
    NotificationChannelRequest
  >({
    mutationFn: (body) =>
      request<NotificationChannelView>("/channels", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.channels }),
  });
}

export function useUpdateNotificationChannel() {
  const qc = useQueryClient();
  return useMutation<
    NotificationChannelView,
    ApiError,
    { channelId: string; body: NotificationChannelRequest }
  >({
    mutationFn: ({ channelId, body }) =>
      request<NotificationChannelView>(`/channels/${channelId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.channels }),
  });
}

export function useDeleteNotificationChannel() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (channelId) =>
      request<void>(`/channels/${channelId}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.channels }),
  });
}

export function useTestNotificationChannel() {
  return useMutation<void, ApiError, string>({
    mutationFn: (channelId) =>
      request<void>(`/channels/${channelId}/test`, { method: "POST" }),
  });
}
