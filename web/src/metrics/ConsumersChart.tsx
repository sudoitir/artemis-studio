import { Text } from '@mantine/core';
import { AreaChart } from '@mantine/charts';
import dayjs from 'dayjs';

import type { MetricSeries } from '../api/client.ts';

/** Consumer count, step-shaped — a drop to zero next to a depth climb is the classic incident. */
export function ConsumersChart({ series, syncId }: { series: MetricSeries | undefined; syncId: string }) {
  if (!series || series.points.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No consumer samples in this window yet.
      </Text>
    );
  }
  const data = series.points.map((p) => ({ ts: dayjs(p.ts).format('MMM D HH:mm'), consumers: p.value }));
  return (
    <AreaChart
      h={220}
      data={data}
      dataKey="ts"
      curveType="step"
      connectNulls={false}
      areaChartProps={{ syncId }}
      series={[{ name: 'consumers', color: 'var(--as-chart-1)' }]}
      gridAxis="y"
    />
  );
}
