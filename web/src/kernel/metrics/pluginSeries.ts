import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useMemo } from 'react';

import { poll } from '../api/polling.ts';
import { ApiError, clusterKey, request } from '../api/request.ts';
import type { components } from '../api/schema.d.ts';
import { rangeSpec, type MetricRange } from '../time/ranges.ts';
import { useServerNow } from '../time/time.ts';

type MetricSeriesResponse = components['schemas']['MetricSeriesResponse'];

/** The live window of a range, advancing a whole bucket at a time (ADR-0055). */
function useLiveWindow(range: MetricRange): { from: string; to: string } {
  const spec = rangeSpec(range);
  const tick = useServerNow(spec.stepMs);
  return useMemo(() => {
    const end = Math.floor(tick / spec.stepMs) * spec.stepMs;
    return { from: new Date(end - spec.windowMs).toISOString(), to: new Date(end).toISOString() };
  }, [tick, spec.stepMs, spec.windowMs]);
}

/**
 * A plugin metric's series for one subject over a live range (ADR-0113). Needs the permission the
 * plugin declared for the metric; without it the read fails like any cluster read the user may
 * not make.
 */
export function usePluginSeries(
  clusterId: string,
  metric: string,
  subject: string,
  range: MetricRange = '1h',
): UseQueryResult<MetricSeriesResponse, ApiError> & { window: { from: string; to: string } } {
  const spec = rangeSpec(range);
  const window = useLiveWindow(range);
  const query = useQuery<MetricSeriesResponse, ApiError>({
    queryKey: clusterKey(clusterId, 'plugin-metric', { metric, subject, ...window }),
    queryFn: () => {
      const sp = new URLSearchParams({ metric, subject, from: window.from, to: window.to, step: spec.step });
      return request<MetricSeriesResponse>(`/clusters/${clusterId}/metrics/plugin?${sp.toString()}`);
    },
    refetchInterval: poll(Math.max(15_000, spec.stepMs)),
    placeholderData: (prev) => prev,
  });
  return Object.assign(query, { window });
}

