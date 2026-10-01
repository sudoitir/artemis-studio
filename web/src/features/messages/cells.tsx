import { Group, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { MessageSummaryView } from './api.ts';

/** The body's preview, and that it is cut short: a preview that looks complete misleads. */
export function BodyPreview({ message: m }: Readonly<{ message: MessageSummaryView }>) {
  return (
    <Group gap="xs" wrap="nowrap">
      <Text size="xs" truncate>
        {m.bodyPreview ?? (
          <Text span c="dimmed">
            (empty)
          </Text>
        )}
      </Text>
      {m.bodyTruncated ? <StatusBadge tone="warning">truncated</StatusBadge> : null}
    </Group>
  );
}
