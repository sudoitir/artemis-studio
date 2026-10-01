import type { ReactNode } from 'react';
import { Group, Stack, Text } from '@mantine/core';

import classes from './ListRows.module.css';

/** A list of {@link Row}s, named for assistive technology. */
export function Rows({
  label,
  children,
  bounded = false,
  blockSize,
}: Readonly<{
  label: string;
  children: ReactNode;
  /** A list that can grow long scrolls inside a named, focusable region of a fixed height instead of stretching the page. */
  bounded?: boolean;
  /**
   * With `bounded`: a height the region always holds, as a CSS length, whatever the number of rows, so a
   * loading frame of the same size is replaced by the list without moving what follows. Without it the
   * region is as tall as its rows, up to about eight.
   */
  blockSize?: string;
}>) {
  const list = (
    <ul className={classes.rows} aria-label={label}>
      {children}
    </ul>
  );
  return bounded ? (
    <section className={classes.bounded} tabIndex={0} aria-label={`${label}, scrollable`} style={{ blockSize }}>
      {list}
    </section>
  ) : (
    list
  );
}

/** One thing a list holds: what it is, the facts about it beneath, and the action for it at the inline end. */
export function Row({ title, facts, action }: Readonly<{ title: ReactNode; facts: ReactNode; action: ReactNode }>) {
  return (
    <li className={classes.row}>
      <Group justify="space-between" align="center" py="xs" gap="md">
        <Stack gap={0}>
          {title}
          <Text size="xs" c="dimmed" className={classes.facts}>
            {facts}
          </Text>
        </Stack>
        {action}
      </Group>
    </li>
  );
}
