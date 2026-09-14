import { Text } from '@mantine/core';

import { ApiKeysPanel } from './ApiKeysPanel.tsx';

/** Account section: the signed-in user's API keys. */
export function ApiKeysSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Long-lived credentials for scripts and assistants, revocable at any time.
      </Text>
      <ApiKeysPanel />
    </>
  );
}
