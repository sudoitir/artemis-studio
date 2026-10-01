import type { PointerEvent } from 'react';
import { IconArrowDown, IconArrowUp } from '@tabler/icons-react';

import type { Column } from './columns.ts';
import { columnSpec } from './measurement.ts';
import type { HeaderSorting } from './sort.ts';
import classes from './DataTable.module.css';

/** The `aria-sort` of a column: none when it cannot sort, else which way it sorts now. */
function ariaSortOf(sorting: HeaderSorting | null): 'ascending' | 'descending' | 'none' | undefined {
  if (!sorting) return undefined;
  if (!sorting.active) return 'none';
  return sorting.desc ? 'descending' : 'ascending';
}

interface HeaderCellProps<T> {
  /** A `div` with the `columnheader` role in the grid, a native `th` in the static table. */
  as: 'div' | 'th';
  column: Column<T>;
  /** Null when the column cannot sort here. */
  sorting: HeaderSorting | null;
  onSort: () => void;
  /** The grid's resize handle and its fit-to-content double-click; the static table has neither. */
  resize?: { onStart: (e: PointerEvent<HTMLElement>) => void; onFit: () => void };
  /** Where the cell is in the grid's focus model, and its 1-based `aria-colindex`. */
  grid?: { col: number; index: number };
  className?: string;
}

/** A column's header cell, shared by both renderers: its name or sort button, and the border handle that resizes it. */
export function HeaderCell<T>({
  as: Tag,
  column,
  sorting,
  onSort,
  resize,
  grid,
  className,
}: Readonly<HeaderCellProps<T>>) {
  const spec = columnSpec(column);
  const name = sorting ? (
    <button type="button" className={classes.sortButton} onClick={onSort}>
      {column.header}
      <span aria-hidden="true" className={classes.sortMark}>
        {sorting.active ? sorting.desc ? <IconArrowDown size="1em" /> : <IconArrowUp size="1em" /> : null}
      </span>
    </button>
  ) : (
    column.header
  );
  return (
    <Tag
      {...(Tag === 'div' ? { role: 'columnheader' } : { scope: 'col' })}
      aria-sort={ariaSortOf(sorting)}
      aria-colindex={grid?.index}
      aria-description={resize ? 'Ctrl+Shift+Left or Right resizes this column.' : undefined}
      data-grid-col={grid?.col}
      data-end={spec.alignEnd || undefined}
      title={column.description}
      className={[classes.cell, classes.headCell, className].filter(Boolean).join(' ')}
    >
      {name}
      {resize ? (
        // A pointer affordance for what Ctrl+Shift+Arrow does from the keyboard.
        <span
          aria-hidden="true"
          className={classes.resizeHandle}
          onPointerDown={resize.onStart}
          onDoubleClick={(e) => {
            e.stopPropagation();
            resize.onFit();
          }}
          onClick={(e) => e.stopPropagation()}
        />
      ) : null}
    </Tag>
  );
}
