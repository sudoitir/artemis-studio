import { Anchor, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useAuthProviders, useMe } from '../../kernel/auth/api.ts';
import { TwoStepSection } from './TwoStepSection.tsx';

/**
 * Account section: where a local account changes its password. Another account's password lives where it
 * signs in, so it is told where, not offered a form that cannot work. While the account is still loading the
 * link is offered.
 */
export function PasswordSection() {
  const me = useMe();
  const providers = useAuthProviders();
  const providerId = me.data?.providerId ?? 'local';
  if (providerId === 'local') {
    return (
      <>
        <Text size="sm" c="dimmed" mb="sm">
          Local accounts only; other accounts change their password where they sign in.
        </Text>
        <Anchor component={Link} to="/change-password" size="sm">
          Change password
        </Anchor>
      </>
    );
  }
  const label = providers.data?.find((p) => p.id === providerId)?.label ?? providerId;
  return (
    <Text size="sm" c="dimmed">
      Your account signs in with {label}, which keeps your password. Change it there.
    </Text>
  );
}

/** Account section: the second factors of an account that signs in with a password. */
export function TwoStepVerificationSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        A second step at sign-in, so a stolen password alone opens nothing.
      </Text>
      <TwoStepSection />
    </>
  );
}
