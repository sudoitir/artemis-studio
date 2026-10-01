import { ActionIcon, Popover, Stack, Text } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';

import type { KeyHelp } from './keyHelp.ts';

/**
 * A small info control beside a setting's label: what the key does and an
 * example, in a popover that opens on click and on keyboard activation — never
 * hover-only, because a disabled or focused input cannot host a tooltip and an
 * operator on a keyboard must be able to reach the explanation (operator-ui).
 */
export function KeyHint({ name, help }: Readonly<{ name: string; help: KeyHelp }>) {
  return (
    <Popover width="22.5rem" position="bottom-start" withArrow shadow="md">
      <Popover.Target>
        <ActionIcon variant="subtle" size="sm" aria-label={`About ${name}`} title={`About ${name}`}>
          <IconInfoCircle size="0.875rem" aria-hidden />
        </ActionIcon>
      </Popover.Target>
      <Popover.Dropdown>
        <Stack gap="xs">
          <Text size="sm" fw={600} ff="monospace">
            {name}
          </Text>
          <Text size="sm">{help.summary}</Text>
          <Text size="sm" c="dimmed">
            <b>Example:</b> {help.example}
          </Text>
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}
