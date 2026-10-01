import { memo, useRef, type CSSProperties, type ReactNode } from 'react';
import { Table } from '@mantine/core';

import type { Column } from './columns.ts';
import type { TableModel } from './DataTable.tsx';
import { HeaderCell } from './HeaderCell.tsx';
import { nextSort, sortingOf } from './sort.ts';
import { MiddleTruncate } from './MiddleTruncate.tsx';
import { cellText, columnSpec, useInlineSize } from './measurement.ts';
import { LoadingLabel, SkeletonTableRows } from './TableStates.tsx';
import classes from './DataTable.module.css';

/** The hover title for a cell, when its value is something a tooltip can say. */
function plainText(value: unknown): string | undefined {
  if (typeof value === 'string') return value || undefined;
  if (typeof value === 'number' || typeof value === 'bigint') return String(value);
  return undefined;
}

function StaticCell<T>({ column, row, first }: Readonly<{ column: Column<T>; row: T; first: boolean }>) {
  const spec = columnSpec(column);
  const value = column.accessor(row);
  const text = cellText(value);
  let content: ReactNode = text;
  if (column.cell) content = column.cell(row);
  else if (spec.truncate === 'middle' && !column.wrap) content = <MiddleTruncate text={text} />;
  const Cell = first ? Table.Th : Table.Td;
  return (
    <Cell
      {...(first ? { scope: 'row' } : {})}
      data-end={spec.alignEnd || undefined}
      data-mono={spec.mono || undefined}
      title={column.wrap ? undefined : plainText(value)}
      className={`${classes.cell} ${classes.staticCell}`}
    >
      <div
        className={column.wrap ? classes.staticWrapText : classes.staticClip}
        style={spec.max === undefined || column.wrap ? undefined : { maxInlineSize: `${spec.max}ch` }}
      >
        {content}
      </div>
    </Cell>
  );
}

function StaticRowView<T>({
  row,
  columns,
  className,
}: Readonly<{ row: T; columns: Column<T>[]; className: string | undefined }>) {
  return (
    <Table.Tr className={className}>
      {columns.map((column, i) => (
        <StaticCell key={column.id} column={column} row={row} first={i === 0} />
      ))}
    </Table.Tr>
  );
}

const StaticRow = memo(StaticRowView) as typeof StaticRowView;

/**
 * The small, read-only renderer of `DataTable` (ADR-0160): a native `<table>` through Mantine's
 * `Table`, with tabular figures, a header that sticks to the page, and the same column model and
 * solver as the grid. The layout is fixed, so the solved widths hold and an identifier or code value
 * is shortened by its kind (middle or end) instead of widening its column. Not virtualised, so cells
 * hold natively focusable controls and the keyboard needs no grid model. `DataTable` switches to the
 * grid above 200 rows.
 */
export function StaticTable<T>({ model, caption }: Readonly<{ model: TableModel<T>; caption?: ReactNode }>) {
  const { columns, data, rowKey } = model;
  const wrapRef = useRef<HTMLDivElement>(null);
  useInlineSize(wrapRef, model.onWidth);
  const placeholder = model.loading && data.length === 0;
  // With `maxRows`, more rows than that scroll inside the frame under a pinned header, so a long list
  // keeps the height its loading placeholder had and the page below it does not move.
  const capped = model.maxRows !== undefined && data.length > model.maxRows;
  const scrolls = model.overflow || capped;

  return (
    <div
      ref={wrapRef}
      className={classes.staticFrame}
      data-overflow={model.overflow || undefined}
      data-capped={capped || undefined}
      style={capped ? ({ '--as-max-rows': model.maxRows } as CSSProperties) : undefined}
      // A frame that scrolls is reachable by keyboard and named, as the grid's scroller is.
      {...(scrolls ? { role: 'region', tabIndex: 0, 'aria-label': `${model.label}, scrollable` } : {})}
    >
      {model.measurer}
      <Table
        layout="fixed"
        tabularNums
        stickyHeader
        stickyHeaderOffset={capped ? '0rem' : 'var(--app-shell-header-offset, 0rem)'}
        className={classes.staticTable}
        aria-label={caption ? undefined : model.label}
        aria-busy={model.loading || undefined}
        style={{ '--as-min-inline': model.overflow ? `${model.minInline}px` : undefined } as CSSProperties}
      >
        {caption ? <Table.Caption>{caption}</Table.Caption> : null}
        <colgroup>
          {columns.map((column) => (
            <col key={column.id} style={{ inlineSize: model.solved[column.id] }} />
          ))}
        </colgroup>
        <Table.Thead>
          <Table.Tr>
            {columns.map((column) => (
              <HeaderCell
                key={column.id}
                as="th"
                column={column}
                sorting={sortingOf(model.sort, column.sortKey, model.onSortChange !== undefined)}
                onSort={() => model.onSortChange?.(nextSort(model.sort, column.sortKey!))}
              />
            ))}
          </Table.Tr>
        </Table.Thead>
        {placeholder ? (
          <SkeletonTableRows columns={columns.length} />
        ) : (
          <Table.Tbody>
            {data.map((row) => (
              <StaticRow key={rowKey(row)} row={row} columns={columns} className={model.rowClassName?.(row)} />
            ))}
          </Table.Tbody>
        )}
      </Table>
      {placeholder ? <LoadingLabel label={model.label} /> : null}
    </div>
  );
}
