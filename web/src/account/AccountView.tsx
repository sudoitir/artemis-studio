import { Anchor, Divider, Stack, Text, Title } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useMe } from '../api/client.ts';
import { ApiKeysPanel } from './ApiKeysPanel.tsx';
import { McpConnectionPanel } from './McpConnectionPanel.tsx';

/**
 * The signed-in user's own page: who you are, how to change your password, the
 * keys you hold, and how to connect an assistant with one.
 *
 * <p>API keys used to live under Administration, which made a per-user
 * credential look like an operator's tool and hid it from everyone without
 * {@code user:admin}. Every user has an account, so this route is ungated.
 */
export function AccountView() {
  const me = useMe();

  return (
    <Stack gap="xl" maw={640} p="lg">
      <Title order={3}>Account</Title>

      <div>
        <Title order={4}>Identity</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Who you are signed in as.
        </Text>
        <Text size="sm">{me.data?.username ?? '—'}</Text>
      </div>

      <Divider />

      <div>
        <Title order={4}>Password</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Local accounts only; an SSO account changes its password with the identity
          provider.
        </Text>
        <Anchor component={Link} to="/change-password" size="sm">
          Change password
        </Anchor>
      </div>

      <Divider />

      <div>
        <Title order={4}>API keys</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Long-lived credentials for scripts and assistants, revocable at any time.
        </Text>
        <ApiKeysPanel />
      </div>

      <Divider />

      <div>
        <Title order={4}>MCP connection</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Point an assistant at this instance.
        </Text>
        <McpConnectionPanel />
      </div>
    </Stack>
  );
}
