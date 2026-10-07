import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { request } from '../api/request.ts';
import type { components } from '../api/schema.d.ts';

type Schemas = components['schemas'];

export type InboxItem = Schemas['ItemView'];
export type InboxPage = Schemas['PageView'];
export type InboxCount = Schemas['CountView'];
export type InboxSeverity = InboxItem['severity'];

/** Which notices a list shows: only the unread ones, or every one still kept. */
export type InboxFilter = 'unread' | 'all';

/** Every inbox query key starts from `inboxKeys.all`, so one invalidation (a stream signal) reaches all of them. */
export const inboxKeys = {
  all: ['inbox'] as const,
  count: ['inbox', 'count'] as const,
  latest: ['inbox', 'latest'] as const,
  list: (filter: InboxFilter) => ['inbox', 'list', filter] as const,
};

/** The count as the badge shows it: "99+" once the server stops counting. */
export function unreadLabel(count: InboxCount | undefined): string {
  if (!count) return '';
  return count.capped ? '99+' : String(count.unread);
}

/**
 * Whether a notice's link is a path inside Studio. The server refuses any other on posting; this is the
 * second check, so a notice can never send the operator to another site.
 */
export function isInAppPath(link: string | null | undefined): link is string {
  return typeof link === 'string' && /^\/[A-Za-z0-9]/.test(link) && !link.includes('//') && !link.includes('\\');
}

/** How many notices the header's popover shows. */
export const LATEST_LIMIT = 10;
/** One page of the inbox page's list. */
const PAGE_LIMIT = 30;

function listPath(filter: InboxFilter, limit: number, before?: number) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (filter === 'unread') params.set('unread', 'true');
  if (before !== undefined) params.set('before', String(before));
  return `/inbox?${params}`;
}

/** How often the count is asked for while the user stream is down. */
const FALLBACK_POLL_MS = 60_000;

/**
 * The unread count the bell shows. While the user stream is `live` it keeps the count current and nothing
 * polls; while it is not, the count is asked for every minute, so a lost connection never freezes it.
 */
export function useInboxCount(live: boolean) {
  return useQuery({
    queryKey: inboxKeys.count,
    queryFn: () => request<InboxCount>('/inbox/count'),
    staleTime: live ? Infinity : 0,
    refetchInterval: live ? false : FALLBACK_POLL_MS,
  });
}

/** The newest notices, read or not, for the header's popover. Fetched only while the popover is open. */
export function useLatestNotices(enabled: boolean) {
  return useQuery({
    queryKey: inboxKeys.latest,
    queryFn: () => request<InboxPage>(listPath('all', LATEST_LIMIT)),
    enabled,
  });
}

/** The inbox page's list, newest first, a page at a time by keyset (`before` the last id shown). */
export function useInboxList(filter: InboxFilter) {
  return useInfiniteQuery({
    queryKey: inboxKeys.list(filter),
    queryFn: ({ pageParam }) => request<InboxPage>(listPath(filter, PAGE_LIMIT, pageParam)),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
  });
}

/** Marks the given notices read, or with `upTo` every notice up to that id (what "Mark all read" sends). */
export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { ids: number[] } | { upTo: number }) =>
      request<{ updated: number }>('/inbox/read', { method: 'POST', body: JSON.stringify(body) }),
    onSettled: () => qc.invalidateQueries({ queryKey: inboxKeys.all }),
  });
}

/** Dismisses one notice: it is deleted, not just read. */
export function useDismissNotice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => request<void>(`/inbox/${id}`, { method: 'DELETE' }),
    onSettled: () => qc.invalidateQueries({ queryKey: inboxKeys.all }),
  });
}
