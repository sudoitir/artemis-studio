import { Text } from '@mantine/core';

import { SessionsManager } from './SessionsManager.tsx';

/** Account section: where the signed-in user is signed in, and how to end a session. */
export function SessionsSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Every browser you are signed in on. A session ends by itself after a period without activity, or when it reaches
        its maximum age.
      </Text>
      <SessionsManager />
    </>
  );
}
