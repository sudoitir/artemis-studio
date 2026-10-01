import type { ReactNode } from 'react';
import { VisuallyHidden } from '@mantine/core';

import classes from './DataTable.module.css';

/** How many placeholder rows a table that is loading shows. */
export const SKELETON_ROWS = 8;

const bars = (count: number) =>
  Array.from({ length: count }, (_, col) => <span key={col} className={classes.skeletonBar} />);

/**
 * The grid's loading rows: placeholders under the header, in the same tracks and at the density's
 * row height, so the page does not move when the rows arrive. Hidden from assistive technology and
 * outside `role="grid"`; `LoadingLabel` says what is loading.
 */
export function SkeletonRows({ tracks }: Readonly<{ tracks: number }>) {
  return (
    <div className={classes.skeleton} aria-hidden="true">
      {Array.from({ length: SKELETON_ROWS }, (_, row) => (
        <div key={row} className={classes.skeletonRow}>
          {bars(tracks)}
        </div>
      ))}
    </div>
  );
}

/** The static table's loading rows. */
export function SkeletonTableRows({ columns }: Readonly<{ columns: number }>) {
  return (
    <tbody aria-hidden="true">
      {Array.from({ length: SKELETON_ROWS }, (_, row) => (
        <tr key={row} className={classes.skeletonTableRow}>
          {Array.from({ length: columns }, (_, col) => (
            <td key={col} className={classes.skeletonCell}>
              <span className={classes.skeletonBar} />
            </td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}

/** What a screen reader hears while the placeholders show. */
export function LoadingLabel({ label }: Readonly<{ label: string }>) {
  return <VisuallyHidden>Loading {label}</VisuallyHidden>;
}

/**
 * The thin bar over a table whose rows stay visible while they are fetched again. Its track is
 * always there, so the table does not move when a refetch starts.
 */
export function RefetchBar({ active }: Readonly<{ active: boolean }>) {
  return (
    <div className={classes.progress} aria-hidden="true">
      {active ? <span className={classes.refetchBar} /> : null}
    </div>
  );
}

/**
 * The empty or error content, a sibling of the grid and never a child of it. `reserveRows` keeps it at
 * least that many rows tall, less the gap the frame puts above it: the height the loading rows had, so
 * a failure in place of them moves nothing below.
 */
export function StateSlot({ children, reserveRows }: Readonly<{ children: ReactNode; reserveRows?: number }>) {
  return (
    <div
      className={classes.state}
      style={
        reserveRows ? { minBlockSize: `calc(var(--as-row-h) * ${reserveRows} - var(--mantine-spacing-xs))` } : undefined
      }
    >
      {children}
    </div>
  );
}
