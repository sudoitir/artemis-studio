import { useEffect, useRef, type ReactNode } from 'react';
import { Menu, Portal } from '@mantine/core';

import classes from './ActionMenu.module.css';
import type { MenuAnchor } from './menuAnchor.ts';

/**
 * One controlled menu opened at a point rather than from a trigger it wraps (ADR-0107). A grid
 * renders one of these for all of its rows: the items of a closed menu are not mounted, so a page
 * of rows costs one menu, not one per row.
 *
 * <p>The anchor is a clipped point element, portalled so that no transformed ancestor (virtualized
 * rows are placed by `transform`) can shift it. It carries `label`, which is what names the menu.
 * Focus is not returned by the menu itself: only the caller knows what opened it, and whether a
 * dialog has taken over.
 */
export function AnchoredMenu({
  opened,
  anchor,
  label,
  onClose,
  children,
}: {
  opened: boolean;
  anchor: MenuAnchor | null;
  /** The menu's accessible name, e.g. "Actions for orders". */
  label: string;
  onClose: () => void;
  children: ReactNode;
}) {
  // A menu opened from the keyboard lands on its first item, as a menu button's does (APG), so the
  // arrow keys and type-ahead work at once.
  const dropdownRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!opened) return;
    const frame = requestAnimationFrame(() => {
      dropdownRef.current?.querySelector<HTMLElement>('[data-menu-item]:not([data-disabled])')?.focus();
    });
    return () => cancelAnimationFrame(frame);
  }, [opened, anchor]);

  if (!anchor) return null;
  const rtl = typeof document !== 'undefined' && document.dir === 'rtl';
  return (
    <Portal>
      <Menu
        opened={opened}
        onChange={(next) => {
          if (!next) onClose();
        }}
        position="bottom-start"
        offset={2}
        shadow="md"
        width={260}
        returnFocus={false}
        // The anchor is a synthetic point this component keeps inside the viewport, and the grid
        // closes the menu on scroll; "hide when the target leaves the screen" has nothing to add.
        hideDetached={false}
        withinPortal
        withInitialFocusPlaceholder={false}
        loop
      >
        <Menu.Target>
          <span
            className={classes.anchor}
            style={{
              insetInlineStart: rtl ? window.innerWidth - anchor.x : anchor.x,
              insetBlockStart: anchor.y,
            }}
          >
            {label}
          </span>
        </Menu.Target>
        <Menu.Dropdown ref={dropdownRef}>{opened ? children : null}</Menu.Dropdown>
      </Menu>
    </Portal>
  );
}
