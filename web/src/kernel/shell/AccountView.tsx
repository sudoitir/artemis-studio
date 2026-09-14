import { Fragment } from 'react';
import { Divider, Stack, Text, Title } from '@mantine/core';

import { useMe } from '../auth/api.ts';
import { useSlot } from '../slots.ts';

/**
 * The signed-in user's own page: who you are, then what the features contribute — how to change
 * your password, the keys you hold, and how to connect an assistant with one.
 *
 * <p>API keys used to live under Administration, which made a per-user
 * credential look like an operator's tool and hid it from everyone without
 * {@code user:admin}. Every user has an account, so this route is ungated.
 */
export function AccountView() {
  const me = useMe();
  const sections = useSlot('account.sections');

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

      {sections.map(({ id, title, Component }) => (
        <Fragment key={id}>
          <Divider />
          <div>
            <Title order={4}>{title}</Title>
            <Component />
          </div>
        </Fragment>
      ))}
    </Stack>
  );
}
