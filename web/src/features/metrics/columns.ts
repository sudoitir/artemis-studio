import { formatInZone } from '../../kernel/metrics/axis.ts';
import type { Column } from '../../ui/table/index.ts';

/** One bucket of the window: its start, and each metric's value in it where there is one. */
export type BucketRow = Record<string, number>;

/** A metric of the window, and the words that head its column. */
export interface BucketMetric {
  name: string;
  label: string;
}

/**
 * The window's table columns: the bucket, which identifies a row, then one number column per metric, in
 * the unit it is written in. A bucket with no sample for a metric reads "—", never zero. A view builds
 * the columns again when the display zone changes.
 */
export function bucketColumns(
  metrics: BucketMetric[],
  format: (name: string, value: number) => string,
  zone: string,
): Column<BucketRow>[] {
  return [
    {
      id: 'bucket',
      header: 'Bucket',
      description: `The bucket's start, in ${zone}`,
      accessor: (row) => formatInZone(row.ts, 'MMM D HH:mm:ss'),
      kind: 'time',
      priority: 'essential',
    },
    ...metrics.map(({ name, label }): Column<BucketRow> => ({
      id: name,
      header: label,
      accessor: (row) => (row[name] === undefined ? '—' : format(name, row[name])),
      kind: 'number',
      priority: 'high',
    })),
  ];
}
