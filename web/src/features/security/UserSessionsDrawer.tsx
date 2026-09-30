import { Drawer, Text } from '@mantine/core';

import { SessionsManager } from './SessionsManager.tsx';

/**
 * One user's signed-in sessions, for an administrator (ADR-0145). Ending a session signs that
 * browser out at its next request. Requires `user:admin`.
 */
export function UserSessionsDrawer({
  user,
  onClose,
}: Readonly<{
  user: { id: string; username: string } | null;
  onClose: () => void;
}>) {
  return (
    <Drawer
      opened={user !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={user ? `Sessions of ${user.username}` : ''}
    >
      {user ? (
        <>
          <Text size="sm" c="dimmed" mb="md">
            Ending a session signs that browser out at its next request. It does not stop the user signing in again;
            disable the account for that.
          </Text>
          <SessionsManager userId={user.id} />
        </>
      ) : null}
    </Drawer>
  );
}
