import { SegmentedControl } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { METRIC_RANGES, type MetricRange } from './ranges.ts';

const LABEL: Record<MetricRange, string> = {
  '15m': '15m',
  '1h': '1h',
  '6h': '6h',
  '24h': '24h',
  '7d': '7d',
};

/** Writes the chosen window to the URL (non-negotiable #9) — shareable, bookmarkable. */
export function RangePicker() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { range?: MetricRange };
  const navigate = useNavigate();
  const current = search.range ?? '1h';

  return (
    <SegmentedControl
      size="xs"
      value={current}
      onChange={(value) =>
        navigate({
          to: '/clusters/$clusterId/metrics',
          params: { clusterId },
          search: { range: value as MetricRange },
        })
      }
      data={METRIC_RANGES.map((r) => ({ label: LABEL[r], value: r }))}
    />
  );
}
