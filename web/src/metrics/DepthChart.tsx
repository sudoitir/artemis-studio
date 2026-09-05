import { Text } from '@mantine/core';
import { CompositeChart } from '@mantine/charts';
import dayjs from 'dayjs';

import type { MetricSeries } from '../api/client.ts';

/** Average queue depth for the window, with the bucket's peak as a dimmed overlay. */
export function DepthChart({ series, syncId }: { series: MetricSeries | undefined; syncId: string }) {
  if (!series || series.points.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No depth samples in this window yet.
      </Text>
    );
  }
  const data = series.points.map((p) => ({
    ts: dayjs(p.ts).format('MMM D HH:mm'),
    depth: p.value,
    peak: p.peak ?? undefined,
  }));
  return (
    <CompositeChart
      h={220}
      data={data}
      dataKey="ts"
      withLegend
      connectNulls={false}
      composedChartProps={{ syncId }}
      series={[
        { name: 'depth', color: 'var(--as-chart-1)', type: 'area' },
        { name: 'peak', color: 'var(--as-chart-3)', type: 'line' },
      ]}
      gridAxis="y"
    />
  );
}
