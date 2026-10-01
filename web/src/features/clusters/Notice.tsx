import type { ReactNode } from 'react';
import { Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import classes from './Clusters.module.css';

/**
 * A fact the operator must read before going on, in a titled block that says it in words. `alert`
 * announces it assertively, for something that changed under the operator; a plain one is announced
 * politely. The tone only emphasises the title.
 */
export function Notice({
  title,
  tone = 'info',
  alert = false,
  children,
}: Readonly<{
  title: string;
  tone?: 'info' | 'warning' | 'danger';
  alert?: boolean;
  children?: ReactNode;
}>) {
  return (
    <div role={alert ? 'alert' : 'status'} className={classes.notice} data-tone={tone}>
      <StatusBadge tone={tone}>{title}</StatusBadge>
      {children ? (
        <Text size="sm" component="div" className={classes.noticeBody}>
          {children}
        </Text>
      ) : null}
    </div>
  );
}
