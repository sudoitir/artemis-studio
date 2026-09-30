import type { ReactNode } from 'react';
import { Divider, Group, Stack, Text } from '@mantine/core';

/** A list of {@link Row}s, named for assistive technology. */
export function Rows({ label, children }: Readonly<{ label: string; children: ReactNode }>) {
  return (
    <Stack component="ul" gap={0} m={0} p={0} aria-label={label} style={{ listStyle: 'none' }}>
      {children}
    </Stack>
  );
}

/** One thing a list holds: what it is, the facts about it beneath, and the action for it at the inline end. */
export function Row({ title, facts, action }: Readonly<{ title: ReactNode; facts: ReactNode; action: ReactNode }>) {
  return (
    <li>
      <Divider />
      <Group justify="space-between" align="center" wrap="nowrap" py="xs" gap="md">
        <Stack gap={2}>
          {title}
          <Text size="xs" c="dimmed">
            {facts}
          </Text>
        </Stack>
        {action}
      </Group>
    </li>
  );
}
