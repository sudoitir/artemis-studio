import type { ReactNode } from 'react';
import { Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import classes from './Notice.module.css';

/**
 * A fact the operator must read before going on, that is not a failure: a titled block that says it
 * in words. `alert` announces it assertively, for something that changed under the operator; a plain
 * one is announced politely.
 */
export function Notice({
  title,
  tone = 'warning',
  alert = false,
  children,
}: Readonly<{
  title: string;
  tone?: 'info' | 'warning';
  alert?: boolean;
  children: ReactNode;
}>) {
  return (
    <div role={alert ? 'alert' : 'status'} className={classes.notice} data-tone={tone}>
      <div>
        <StatusBadge tone={tone}>{title}</StatusBadge>
      </div>
      <Text size="sm" component="div">
        {children}
      </Text>
    </div>
  );
}
