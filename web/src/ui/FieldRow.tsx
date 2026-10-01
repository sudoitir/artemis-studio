import type { ReactNode } from 'react';

import classes from './FieldRow.module.css';

/**
 * Fields that belong on one line, such as a username and password, or a field and the button that
 * submits it. Every field's input box stays on the same line whatever its label or message holds,
 * and the row wraps when the window is narrow or zoomed.
 */
export function FieldRow({ children }: Readonly<{ children: ReactNode }>) {
  return <div className={classes.row}>{children}</div>;
}
