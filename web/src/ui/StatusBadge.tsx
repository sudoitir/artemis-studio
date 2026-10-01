import classes from './StatusBadge.module.css';

/**
 * A state, always in words. `children` is the word ("Degraded", "Stopped"); the tone only
 * emphasises it, so the badge reads the same without colour. Healthy and neutral states stay quiet:
 * colour appears for `warning` and `danger` only.
 */
export function StatusBadge({
  tone = 'neutral',
  children,
}: Readonly<{
  /** `neutral` for a state that needs no attention, `info` for a notable one, `warning` and `danger` for a fault. */
  tone?: 'neutral' | 'info' | 'warning' | 'danger';
  /** The state, as a word or short phrase. */
  children: string;
}>) {
  return (
    <span className={classes.badge} data-tone={tone}>
      {children}
    </span>
  );
}
