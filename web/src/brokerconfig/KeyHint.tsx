import { ActionIcon, Popover, Stack, Text } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';

import type { KeyHelp } from './keyHelp.ts';

/**
 * A small info control beside a setting's label: what the key does and an
 * example, in a popover that opens on click and on keyboard activation — never
 * hover-only, because a disabled or focused input cannot host a tooltip and an
 * operator on a keyboard must be able to reach the explanation (operator-ui).
 */
export function KeyHint({ name, help }: { name: string; help: KeyHelp }) {
  return (
    <Popover width={360} position="bottom-start" withArrow shadow="md">
      <Popover.Target>
        <ActionIcon
          variant="subtle"
          color="gray"
          size="xs"
          aria-label={`About ${name}`}
          title={`About ${name}`}
        >
          <IconInfoCircle size={14} aria-hidden />
        </ActionIcon>
      </Popover.Target>
      <Popover.Dropdown>
        <Stack gap={6}>
          <Text size="xs" fw={600} ff="monospace">
            {name}
          </Text>
          <Text size="xs">{help.summary}</Text>
          <Text size="xs" c="dimmed">
            <b>Example:</b> {help.example}
          </Text>
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}
