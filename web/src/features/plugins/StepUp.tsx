import { useState } from 'react';
import { Alert, Button, Group, PasswordInput, Stack, Text } from '@mantine/core';

import { useMe } from '../../kernel/auth/api.ts';
import { useReauthenticate } from './api.ts';
import { useFreshSignIn } from './freshSignIn.ts';

/**
 * "Confirm it is you" (ADR-0103): installing or changing a plugin runs code on the server, so a
 * session that signed in more than five minutes ago confirms first — with its password, or by
 * signing in again at its identity provider, which brings the operator back to `returnTo`.
 * Renders nothing once the session is fresh.
 */
export function StepUp({ returnTo }: { returnTo: string }) {
  const me = useMe();
  const fresh = useFreshSignIn();
  const reauthenticate = useReauthenticate();
  const [password, setPassword] = useState('');
  const [empty, setEmpty] = useState(false);
  const reauth = me.data?.reauthentication;
  if (fresh || !reauth) return null;

  if (reauth.method === 'REDIRECT' && reauth.startPath) {
    const href = `${reauth.startPath}&returnTo=${encodeURIComponent(returnTo)}`;
    return (
      <Alert variant="light" title="Confirm it is you">
        <Stack gap="xs">
          <Text size="sm">
            Sign in again with your identity provider to continue. You come back here, and nothing you
            reviewed is lost.
          </Text>
          <Button component="a" href={href} w="fit-content">
            Sign in again
          </Button>
        </Stack>
      </Alert>
    );
  }

  const failed = reauthenticate.error;
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!password) {
          setEmpty(true);
          return;
        }
        reauthenticate.mutate(password, { onSuccess: () => setPassword('') });
      }}
    >
      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Confirm it is you
        </Text>
        <Text size="sm" c="dimmed">
          Your last sign-in was more than five minutes ago. Re-enter your password; after five wrong
          attempts you are signed out.
        </Text>
        <Group align="flex-end" gap="xs">
          <PasswordInput
            label="Your password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => {
              setPassword(e.currentTarget.value);
              setEmpty(false);
            }}
            error={empty ? 'Enter your password.' : failed ? failed.message : undefined}
            w={260}
          />
          <Button type="submit" loading={reauthenticate.isPending}>
            Confirm
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
