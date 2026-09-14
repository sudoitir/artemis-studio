import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, clusterKey, request } from "../../kernel/api/request.ts";
import { poll } from "../../kernel/api/polling.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type AffectedView = Schemas["AffectedView"];
export type DlqQueue = Schemas["DlqQueue"];
export type DlqView = Schemas["DlqView"];
export type DryRunView = Schemas["DryRunView"];
export type MessageActionRequest = Schemas["MessageActionRequest"];
export type MessageDetailView = Schemas["MessageDetailView"];
export type MessagePageView = Schemas["MessagePageView"];
export type MessageSummaryView = Schemas["MessageSummaryView"];
export type PartialView = Schemas["PartialView"];
export type SendMessageRequest = Schemas["SendMessageRequest"];

export type MessageActionKind = "move" | "retry" | "delete" | "expire";

export const keys = {
  message: (id: string, queueName: string, messageId: string, node?: string, filter?: string) =>
    clusterKey(id, 'queues', queueName, 'messages', messageId, { node, filter }),
  messages: (id: string, queueName: string, params: MessageBrowseParams = {}) =>
    clusterKey(id, 'queues', queueName, 'messages', params),
  topic: (id: string, topic: string) => clusterKey(id, topic),
};

export interface MessageBrowseParams {
  node?: string;
  filter?: string;
  page?: number;
  size?: number;
}

function messageSearch(params: MessageBrowseParams): string {
  const sp = new URLSearchParams();
  if (params.node) sp.set("node", params.node);
  if (params.filter) sp.set("filter", params.filter);
  if (params.page && params.page > 1) sp.set("page", String(params.page));
  if (params.size) sp.set("size", String(params.size));
  const s = sp.toString();
  return s ? `?${s}` : "";
}

export function useMessages(
  clusterId: string,
  queueName: string,
  params: MessageBrowseParams = {},
): UseQueryResult<MessagePageView, ApiError> {
  return useQuery({
    queryKey: keys.messages(clusterId, queueName, params),
    queryFn: () =>
      request<MessagePageView>(
        `/clusters/${clusterId}/queues/${encodeURIComponent(queueName)}/messages${messageSearch(params)}`,
      ),
    placeholderData: (prev) => prev,
  });
}

export function useMessageDetail(
  clusterId: string,
  queueName: string,
  messageId: string | null,
  node?: string,
  filter?: string,
): UseQueryResult<MessageDetailView, ApiError> {
  return useQuery({
    queryKey: keys.message(
      clusterId,
      queueName,
      messageId ?? "none",
      node,
      filter,
    ),
    queryFn: () => {
      const sp = new URLSearchParams();
      if (node) sp.set("node", node);
      if (filter) sp.set("filter", filter);
      const qs = sp.toString();
      return request<MessageDetailView>(
        `/clusters/${clusterId}/queues/${encodeURIComponent(queueName)}/messages/${messageId}${qs ? `?${qs}` : ""}`,
      );
    },
    enabled: messageId !== null,
  });
}

/** Extra `type` on `ApiError` for the 422 bulk-cap problem so the UI can show the count/cap. */
export interface BulkCapProblem {
  affectedCount: number;
  cap: number;
}

function messagesBase(clusterId: string, queueName: string) {
  return `/clusters/${clusterId}/queues/${encodeURIComponent(queueName)}/messages`;
}

function mutationQuery(
  node?: string,
  dryRun?: boolean,
  override?: boolean,
): string {
  const sp = new URLSearchParams();
  if (node) sp.set("node", node);
  if (dryRun) sp.set("dryRun", "true");
  if (override) sp.set("override", "true");
  const s = sp.toString();
  return s ? `?${s}` : "";
}

export interface SendVars {
  body: SendMessageRequest;
  node?: string;
  dryRun?: boolean;
}

export function useSendMessage(clusterId: string, queueName: string) {
  const qc = useQueryClient();
  return useMutation<AffectedView | DryRunView, ApiError, SendVars>({
    mutationFn: ({ body, node, dryRun }) =>
      request(
        `${messagesBase(clusterId, queueName)}${mutationQuery(node, dryRun)}`,
        {
          method: "POST",
          body: JSON.stringify(body),
        },
      ),
    onSuccess: (result) => {
      // DryRunView carries `cap`; AffectedView does not. Only a real run changes state.
      if ("cap" in result) return;
      qc.invalidateQueries({ queryKey: keys.messages(clusterId, queueName) });
      qc.invalidateQueries({ queryKey: keys.topic(clusterId, "queues") });
    },
  });
}

export interface ActionVars {
  action: MessageActionKind;
  body: MessageActionRequest;
  node?: string;
  dryRun?: boolean;
  override?: boolean;
}

export function useMessageAction(clusterId: string, queueName: string) {
  const qc = useQueryClient();
  return useMutation<AffectedView | DryRunView | PartialView, ApiError, ActionVars>({
    mutationFn: ({ action, body, node, dryRun, override }) =>
      request(
        `${messagesBase(clusterId, queueName)}/actions/${action}${mutationQuery(node, dryRun, override)}`,
        { method: "POST", body: JSON.stringify(body) },
      ),
    onSuccess: (result) => {
      // DryRunView carries `cap`; AffectedView does not. Only a real run changes state.
      if ("cap" in result) return;
      qc.invalidateQueries({ queryKey: keys.messages(clusterId, queueName) });
      qc.invalidateQueries({ queryKey: keys.topic(clusterId, "queues") });
    },
  });
}

export interface PurgeVars {
  node?: string;
  dryRun?: boolean;
  override?: boolean;
}

export function usePurgeQueue(clusterId: string, queueName: string) {
  const qc = useQueryClient();
  return useMutation<AffectedView | DryRunView, ApiError, PurgeVars>({
    mutationFn: ({ node, dryRun, override }) =>
      request(
        `${messagesBase(clusterId, queueName)}${mutationQuery(node, dryRun, override)}`,
        {
          method: "DELETE",
        },
      ),
    onSuccess: (result) => {
      // DryRunView carries `cap`; AffectedView does not. Only a real run changes state.
      if ("cap" in result) return;
      qc.invalidateQueries({ queryKey: keys.messages(clusterId, queueName) });
      qc.invalidateQueries({ queryKey: keys.topic(clusterId, "queues") });
    },
  });
}

export function useDlq(clusterId: string): UseQueryResult<DlqView, ApiError> {
  return useQuery({
    queryKey: ["clusters", clusterId, "dlq"],
    queryFn: () => request<DlqView>(`/clusters/${clusterId}/dlq`),
    refetchInterval: poll(10_000),
  });
}
