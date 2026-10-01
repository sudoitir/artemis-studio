import { useMemo } from 'react';

import type { MetricSeries } from './api.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { mergeByTimestamp } from '../../kernel/metrics/axis.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { bucketColumns, type BucketMetric, type BucketRow } from './columns.ts';

const rowKey = (row: BucketRow) => String(row.ts);

/** A metric of the window with its series, which may be absent when nothing was sampled. */
export interface WindowMetric extends BucketMetric {
  series: MetricSeries | undefined;
}

/**
 * The displayed window as rows.
 *
 * A rendered plot is not reachable by every operator, and a crosshair is a poor
 * way to read an exact value even when it is. Newest first, because the question
 * that brings someone to this page is almost always about now.
 */
export function MetricsTable({
  metrics,
  format,
}: Readonly<{
  metrics: WindowMetric[];
  format: (name: string, value: number) => string;
}>) {
  // Timestamps below are formatted in the display zone, which the columns are built again for.
  const zone = useDisplayZone();
  const rows = useMemo(
    () => mergeByTimestamp(metrics.map((m) => ({ name: m.name, series: m.series }))).reverse(),
    [metrics],
  );
  const columns = useMemo(
    () =>
      bucketColumns(
        metrics.map(({ name, label }) => ({ name, label })),
        format,
        zone,
      ),
    [metrics, format, zone],
  );

  return (
    <DataTable
      variant="static"
      label="Metric buckets"
      storageKey="metrics.buckets"
      height={{ maxRows: 12 }}
      columns={columns}
      data={rows}
      rowKey={rowKey}
      empty={
        <EmptyState
          kind="empty"
          title="No buckets in this window"
          description="A bucket exists once the metric has been sampled in it. Widen the range, or wait for the next sample."
        />
      }
    />
  );
}
