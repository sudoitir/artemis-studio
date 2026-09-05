import type { ReactNode } from 'react';
import { Tooltip } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import styles from './NavItem.module.css';

/**
 * One sidebar row, shared by the cluster switcher and the per-cluster view nav
 * (ADR-0034). Collapsed state hides the label visually but never from a screen
 * reader — `aria-label` carries it, and the `Tooltip` supplies the mouse
 * equivalent, opened late enough (350ms) that sweeping the rail doesn't flicker
 * a tooltip per row.
 */
export function NavItem({
  to,
  label,
  leading,
  trailing,
  collapsed,
}: {
  to: string;
  label: string;
  /** An icon, a health mark, or a monogram — whatever leads the row. */
  leading: ReactNode;
  trailing?: ReactNode;
  collapsed: boolean;
}) {
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
