import { useId, type ReactNode } from 'react';
import { VisuallyHidden } from '@mantine/core';

import classes from './Stat.module.css';

/**
 * A labelled figure. A `null` value renders "Unavailable", with `unavailableReason` written out
 * beneath it so the reason is read by every route (keyboard, screen reader, pointer), never "0":
 * an absent number that reads as zero is the most dangerous misreading. A `0` value is a real zero.
 *
 * <p>Figures are tabular, so a change between refreshes is visible without reading digits. While
 * `loading`, the figure's place is reserved at the size it will have, so what arrives does not move
 * the page.
 */
export function Stat({
  label,
  value,
  unit,
  unavailableReason,
  loading = false,
}: Readonly<{
  label: string;
  /** The figure, or `null` when it is not known. */
  value: ReactNode;
  /** Shown after the figure, such as "msg/s". Omitted while the figure is unavailable. */
  unit?: string;
  /** Why the figure is not known; shown only when `value` is `null`. */
  unavailableReason?: string;
  loading?: boolean;
}>) {
  const reasonId = useId();
  const unavailable = value === null || value === undefined;
  const reason = unavailable && !loading ? unavailableReason : undefined;
  let figure: ReactNode;
  if (loading) {
    figure = (
      <>
        <span className={classes.skeleton} aria-hidden="true" />
        <VisuallyHidden>Loading</VisuallyHidden>
      </>
    );
  } else if (unavailable) {
    figure = <span className={classes.unavailable}>Unavailable</span>;
  } else {
    figure = (
      <>
        {value}
        {unit ? <span className={classes.unit}>{unit}</span> : null}
      </>
    );
  }
  return (
    <dl className={classes.root} aria-busy={loading || undefined}>
      <dt className={classes.label}>{label}</dt>
      <dd className={classes.value} aria-describedby={reason ? reasonId : undefined}>
        {figure}
      </dd>
      {reason ? (
        <dd id={reasonId} className={classes.reason}>
          {reason}
        </dd>
      ) : null}
    </dl>
  );
}
