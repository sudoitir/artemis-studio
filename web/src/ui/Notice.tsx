import type { ReactNode } from 'react';
import { Text } from '@mantine/core';

import { StatusBadge } from './StatusBadge.tsx';
import classes from './Notice.module.css';

/**
 * A fact the operator must read before going on, in a titled block that says it in words: the title
 * is a `StatusBadge`, so it reads the same without colour, and the edge only repeats the tone.
 *
 * <p>The announcement follows the tone. `danger` is an alert, announced assertively because
 * something went wrong or changed under the operator; every other tone is a status, announced
 * politely. `action` is the next step as a control, such as a Retry or Review button.
 */
export function Notice({
  title,
  tone = 'neutral',
  action,
  children,
}: Readonly<{
  /** What the notice is about, in words: "Studio needs a restart". */
  title: string;
  tone?: 'neutral' | 'info' | 'warning' | 'danger';
  /** The next step, as a control. */
  action?: ReactNode;
  /** The body: what it means and what to do. */
  children?: ReactNode;
}>) {
  const content = (
    <>
      <StatusBadge tone={tone}>{title}</StatusBadge>
      {children ? (
        <Text size="sm" component="div" className={classes.body}>
          {children}
        </Text>
      ) : null}
      {action ? <div className={classes.action}>{action}</div> : null}
    </>
  );
  // A danger notice interrupts (alert); every other tone is a polite status, which is what <output> is.
  return tone === 'danger' ? (
    <div role="alert" className={classes.notice} data-tone={tone}>
      {content}
    </div>
  ) : (
    <output className={classes.notice} data-tone={tone}>
      {content}
    </output>
  );
}
