import { useId, type ReactNode } from 'react';
import { Tooltip, VisuallyHidden } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useTruncated } from '../../ui/useTruncated.ts';
import styles from './NavItem.module.css';

/**
 * One navigation row, the only one: the sidebar's views and every section list (Settings,
 * Administration, Configuration) use it, so they look and behave alike. It is a full-width block whose
 * whole surface is the link, with its label on the same start edge as the group heading above it
 * (`NavGroup`). Collapsed state hides the label visually but never from a screen reader: `aria-label`
 * carries it, and the `Tooltip` supplies the mouse equivalent. The navbar's `Tooltip.Group` sets the
 * delay: late enough that sweeping the rail doesn't flicker a tooltip per row, then instant from row to
 * row. A label that is cut off by its ellipsis gets a `title`.
 *
 * A row with a `disabledReason` is not a link. It stays in the tab order, so the reason is reachable by
 * keyboard: the tooltip opens on focus as well as hover, and a screen reader hears the reason as the
 * row's description.
 *
 * `current` decides whether the row is the open one when the address alone cannot (a section list's
 * open section is a search value, and the first one is open when there is none); without it the router
 * decides from the path. The open row is marked `aria-current="page"`.
 */
export function NavItem({
  to,
  search,
  current,
  label,
  leading,
  trailing,
  collapsed = false,
  disabledReason,
  onIntent,
}: Readonly<{
  to: string;
  /** The search the link carries; a section list gives the section's. */
  search?: Record<string, unknown>;
  current?: boolean;
  label: string;
  /** An icon, a health mark, or a monogram — whatever leads the row. Section rows have none. */
  leading?: ReactNode;
  trailing?: ReactNode;
  collapsed?: boolean;
  /** Why the row cannot be opened; the row is disabled while this is set. */
  disabledReason?: string;
  /** The pointer or focus reached the row: a chance to load what it opens. */
  onIntent?: () => void;
}>) {
  const reasonId = useId();
  const { ref, truncated } = useTruncated<HTMLSpanElement>(label);
  const labelNode = (
    <span ref={ref} className={styles.label} title={truncated ? label : undefined}>
      {label}
    </span>
  );
  const leadingNode = leading ? (
    <span className={styles.leading} aria-hidden="true">
      {leading}
    </span>
  ) : null;

  if (disabledReason) {
    // The reason sits beside the row rather than in it, so it describes the row without
    // becoming part of its name.
    return (
      <>
        <Tooltip
          label={disabledReason}
          position="right"
          events={{ hover: true, focus: true, touch: true }}
          multiline
          w={240}
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
            {leadingNode}
            {labelNode}
          </span>
        </Tooltip>
        <VisuallyHidden id={reasonId}>{disabledReason}</VisuallyHidden>
      </>
    );
  }

  const explicit = current !== undefined;
  return (
    <Tooltip label={label} position="right" disabled={!collapsed}>
      <Link
        to={to}
        search={search as never}
        // Choosing a section keeps the page where it is; the section list scrolls it when it must.
        resetScroll={explicit ? false : undefined}
        className={styles.item}
        data-collapsed={collapsed || undefined}
        data-active={explicit && current ? 'true' : undefined}
        aria-current={explicit && current ? 'page' : undefined}
        activeOptions={{ exact: false }}
        activeProps={explicit ? undefined : { 'data-active': 'true', 'aria-current': 'page' }}
        aria-label={collapsed ? label : undefined}
        onPointerEnter={onIntent}
        onFocus={onIntent}
      >
        {leadingNode}
        {labelNode}
        {trailing ? <span className={styles.trailing}>{trailing}</span> : null}
      </Link>
    </Tooltip>
  );
}
