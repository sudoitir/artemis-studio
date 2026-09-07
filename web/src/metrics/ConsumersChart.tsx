import { AreaChart } from '@mantine/charts';

import type { MetricSeries } from '../api/client.ts';
import { CHART_HEIGHT } from './ChartPanel.tsx';
import {
  formatExact,
  gridProps,
  labelFormatter,
  mergeByTimestamp,
  timeAxisProps,
  yAxisProps,
} from './axis.ts';
import type { MetricRange } from './ranges.ts';

/** Consumer count, step-shaped — a drop to zero next to a depth climb is the classic incident. */
export function ConsumersChart({
  series,
  range,
  from,
  to,
  syncId,
}: {
  series: MetricSeries | undefined;
  range: MetricRange;
  from: number;
  to: number;
  syncId: string;
}) {
  const data = mergeByTimestamp([{ name: 'consumers', series }]);

  return (
    <AreaChart
      h={CHART_HEIGHT}
      data={data}
      dataKey="ts"
      curveType="step"
      connectNulls={false}
      withDots={false}
      areaChartProps={{ syncId }}
      valueFormatter={formatExact}
      xAxisProps={timeAxisProps(range, from, to)}
      yAxisProps={yAxisProps({ integral: true })}
      gridProps={gridProps()}
      tooltipProps={{ labelFormatter: (label) => labelFormatter(range)(Number(label)) }}
      series={[{ name: 'consumers', label: 'Consumers', color: 'var(--as-chart-1)' }]}
      gridAxis="y"
    />
  );
}
