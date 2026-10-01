import { useMemo } from 'react';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { DataTable } from '../../ui/table/index.ts';
import type { SqlRowView } from './api.ts';
import { resultColumns } from './columns.ts';
import { rowKey } from './useSqlTail.ts';
import classes from './ResultGrid.module.css';

/**
 * The result set, in the console's data table: the same sizing, sorting and node attribution as every
 * other tabular view.
 *
 * <p>The leading column is where the row came from, not what it says: a live row
 * and an indexed row mean different things, and which one an operator is looking
 * at has to be answerable without opening it.
 */
export function ResultGrid({
  clusterId,
  rows,
  onOpen,
  emptyLabel,
  freshKeys,
  columnIds,
  onAtTopChange,
}: Readonly<{
  clusterId: string;
  rows: SqlRowView[];
  onOpen: (row: SqlRowView) => void;
  /** Says why there are no rows, and what to do next: the grid shows it in their place. */
  emptyLabel: React.ReactNode;
  /** Keys the live tail delivered in the last few seconds. */
  freshKeys?: ReadonlySet<string>;
  /** The columns to show, in the order to show them. Defaults to all of them. */
  columnIds?: readonly string[];
  onAtTopChange?: (atTop: boolean) => void;
}>) {
  // The Enqueued column is an absolute timestamp, so this view follows the display zone.
  const zone = useDisplayZone();
  const columns = useMemo(() => {
    const all = resultColumns(clusterId, zone);
    if (!columnIds) return all;
    // Ordered by the caller's list, not by the definition order: reordering is the
    // point, and a column the caller left out is simply not built.
    return columnIds.flatMap((id) => all.filter((c) => c.id === id));
  }, [clusterId, zone, columnIds]);
  return (
    <DataTable
      label="Query results"
      storageKey="sql.results"
      height="fill"
      columns={columns}
      data={rows}
      rowKey={rowKey}
      onRowClick={onOpen}
      empty={emptyLabel}
      rowClassName={(r) => (freshKeys?.has(rowKey(r)) ? classes.fresh : undefined)}
      onAtTopChange={onAtTopChange}
    />
  );
}
