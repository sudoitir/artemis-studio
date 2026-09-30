import { useMutation, useQuery, type UseQueryResult } from '@tanstack/react-query';

import { ApiError, BASE, csrfToken, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type SummaryView = Schemas['SummaryView'];
export type BundleView = Schemas['BundleView'];
export type SectionView = Schemas['SectionView'];

/** The environment a bug report carries. Read from Studio only, when the report dialog opens. */
export function useDiagnosticsSummary(enabled: boolean): UseQueryResult<SummaryView, ApiError> {
  return useQuery({
    queryKey: ['diagnostics', 'summary'],
    queryFn: () => request<SummaryView>('/diagnostics/summary'),
    enabled,
    staleTime: Infinity,
  });
}

/** Gathers and redacts every section into a snapshot the download is later written from. */
export function usePrepareBundle() {
  return useMutation<BundleView, ApiError, void>({
    mutationFn: () => request<BundleView>('/admin/diagnostics/bundles', { method: 'POST' }),
  });
}

export interface DownloadedBundle {
  fileName: string;
  blob: Blob;
}

/** The kept sections of a prepared snapshot, as a zip. Raw fetch: the body is binary, not JSON. */
export async function fetchBundle(id: string, sections: string[]): Promise<DownloadedBundle> {
  const headers: Record<string, string> = { 'content-type': 'application/json', accept: 'application/zip' };
  const token = csrfToken();
  if (token) headers['X-XSRF-TOKEN'] = token;
  const res = await fetch(`${BASE}/admin/diagnostics/bundles/${encodeURIComponent(id)}/download`, {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body: JSON.stringify({ sections }),
  });
  if (!res.ok) {
    let body: Record<string, unknown> = {};
    try {
      body = JSON.parse(await res.text()) as Record<string, unknown>;
    } catch {
      /* not a problem body */
    }
    throw new ApiError(res.status, body);
  }
  const disposition = res.headers.get('content-disposition') ?? '';
  const fileName = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? 'diagnostics.zip';
  return { fileName, blob: await res.blob() };
}

export function useDownloadBundle() {
  return useMutation<DownloadedBundle, ApiError, { id: string; sections: string[] }>({
    mutationFn: ({ id, sections }) => fetchBundle(id, sections),
  });
}
