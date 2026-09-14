import { Text } from '@mantine/core';

import { McpConnectionPanel } from './McpConnectionPanel.tsx';

/** Account section: how to point an assistant at this instance. */
export function McpSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Point an assistant at this instance.
      </Text>
      <McpConnectionPanel />
    </>
  );
}
