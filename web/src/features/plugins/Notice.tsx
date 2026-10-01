import type { ReactNode } from 'react';
import { Text } from '@mantine/core';

import { violationsOf } from './api.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import styles from './Plugins.module.css';

/**
 * A fact the operator must read before going on, in a titled block that says it in words. `alert`
 * announces it assertively, for something that changed under the operator or went wrong; a plain
 * one is announced politely. The tone only emphasises the title.
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
    <div role={alert ? 'alert' : 'status'} className={styles.notice} data-tone={tone}>
      <StatusBadge tone={tone}>{title}</StatusBadge>
      {children ? (
        <Text size="sm" component="div" className={styles.noticeBody}>
          {children}
        </Text>
      ) : null}
    </div>
  );
}

/**
 * Why the server refused a plugin action, with what to do about it. The plugin endpoints list their
 * reasons as violations, each with the fix; any other failure reads as an `ErrorState`.
 */
export function Refusal({ error, title = 'Not done' }: Readonly<{ error: unknown; title?: string }>) {
  const violations = violationsOf(error);
  if (violations.length === 0) return <ErrorState variant="inline" error={error} />;
  return (
    <Notice title={title} tone="danger" alert>
      <ul className={styles.violations}>
        {violations.map((v) => (
          <li key={v.code + v.message}>
            {v.message}
            {v.fix ? <span className={styles.note}> Next: {v.fix}</span> : null}
          </li>
        ))}
      </ul>
    </Notice>
  );
}
