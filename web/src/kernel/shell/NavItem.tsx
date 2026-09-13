import { useId, type ReactNode } from 'react';
import { Tooltip, VisuallyHidden } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import styles from './NavItem.module.css';

/**
 * One sidebar row, shared by the cluster switcher and the per-cluster view nav
 * (ADR-0034). Collapsed state hides the label visually but never from a screen
 * reader — `aria-label` carries it, and the `Tooltip` supplies the mouse
 * equivalent, opened late enough (350ms) that sweeping the rail doesn't flicker
 * a tooltip per row.
 *
 * A row with a `disabledReason` is not a link. It stays in the tab order, so the
 * reason is reachable by keyboard: the tooltip opens on focus as well as hover,
 * and a screen reader hears the reason as the row's description.
 */
export function NavItem({
  to,
  label,
  leading,
  trailing,
  collapsed,
  disabledReason,
}: {
  to: string;
  label: string;
  /** An icon, a health mark, or a monogram — whatever leads the row. */
  leading: ReactNode;
  trailing?: ReactNode;
  collapsed: boolean;
  /** Why the row cannot be opened; the row is disabled while this is set. */
  disabledReason?: string;
}) {
  const reasonId = useId();

  if (disabledReason) {
    // The reason sits beside the row rather than in it, so it describes the row without
    // becoming part of its name.
    return (
      <>
        <Tooltip
          label={disabledReason}
          position="right"
          openDelay={350}
          events={{ hover: true, focus: true, touch: true }}
          multiline
          w={240}
          withArrow
        >
          <span
            className={styles.item}
            data-collapsed={collapsed || undefined}
            data-disabled="true"
            role="link"
            aria-disabled="true"
            tabIndex={0}
            aria-label={collapsed ? label : undefined}
            aria-describedby={reasonId}
          >
            <span className={styles.leading} aria-hidden="true">
              {leading}
            </span>
            <span className={styles.label}>{label}</span>
          </span>
        </Tooltip>
        <VisuallyHidden id={reasonId}>{disabledReason}</VisuallyHidden>
      </>
    );
  }

  return (
    <Tooltip label={label} position="right" openDelay={350} disabled={!collapsed} withArrow>
      <Link
        to={to}
        className={styles.item}
        data-collapsed={collapsed || undefined}
        activeOptions={{ exact: false }}
        activeProps={{ 'data-active': 'true', 'aria-current': 'page' }}
        aria-label={collapsed ? label : undefined}
      >
        <span className={styles.leading} aria-hidden="true">
          {leading}
        </span>
        <span className={styles.label}>{label}</span>
        {trailing ? <span className={styles.trailing}>{trailing}</span> : null}
      </Link>
    </Tooltip>
  );
}
