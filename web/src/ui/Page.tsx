import type { ReactNode } from 'react';

import classes from './Page.module.css';

/**
 * The frame of a page: its parts in one column, spaced by the theme's spacing scale.
 *
 * <p>`fill` gives the last child the height that remains in the window below the application
 * header, for a view whose last part is a grid or a canvas that should end at the window's edge.
 * Without it the page is as tall as its content.
 */
export function Page({
  fill = false,
  children,
}: Readonly<{
  /** The last child takes the remaining window height instead of its content height. */
  fill?: boolean;
  children: ReactNode;
}>) {
  return (
    <div className={classes.page} data-fill={fill || undefined}>
      {children}
    </div>
  );
}
