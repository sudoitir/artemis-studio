import { Text } from '@mantine/core';
import { CompositeChart } from '@mantine/charts';
import dayjs from 'dayjs';

import type { MetricSeries } from '../api/client.ts';

/**
 * `messagesAdded` vs `messagesAcked`, both already rates (msg/s) from the API —
 * divergence between the two lines *is* the backlog signal. The two series can
 * carry slightly different bucket sets (a metric with no samples in a bucket
 * just omits the point), so they are merged by timestamp rather than assumed
 * to line up index-for-index.
 *
 * ponytail: syncId keeps cross-chart tooltips aligned by index, so a bucket
 * present in this chart but absent from a sibling chart can shift the sync by
 * one point. Acceptable for now — revisit if it's ever visibly wrong.
 */
export function ThroughputChart({
  added,
  acked,
  syncId,
}: {
  added: MetricSeries | undefined;
  acked: MetricSeries | undefined;
  syncId: string;
}) {
  if ((!added || added.points.length === 0) && (!acked || acked.points.length === 0)) {
    return (
      <Text size="sm" c="dimmed">
        No throughput samples in this window yet.
      </Text>
    );
  }
  const rows = new Map<string, { ts: string; added?: number; acked?: number }>();
  for (const p of added?.points ?? []) {
    const label = dayjs(p.ts).format('MMM D HH:mm');
    rows.set(p.ts, { ...rows.get(p.ts), ts: label, added: p.value });
  }
  for (const p of acked?.points ?? []) {
    const label = dayjs(p.ts).format('MMM D HH:mm');
    rows.set(p.ts, { ...rows.get(p.ts), ts: label, acked: p.value });
  }
  const data = [...rows.keys()].sort().map((k) => rows.get(k)!);

  return (
    <CompositeChart
      h={220}
      data={data}
      dataKey="ts"
      withLegend
      connectNulls={false}
      composedChartProps={{ syncId }}
      valueFormatter={(v) => `${v.toFixed(1)} msg/s`}
      series={[
        { name: 'added', color: 'var(--as-chart-1)', type: 'line' },
        { name: 'acked', color: 'var(--as-chart-2)', type: 'line' },
      ]}
      gridAxis="y"
    />
  );
}
