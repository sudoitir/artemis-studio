import { Anchor, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { TwoStepSection } from './TwoStepSection.tsx';

/** Account section: where a local account changes its password. */
export function PasswordSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Local accounts only; an SSO account changes its password with the identity provider.
      </Text>
      <Anchor component={Link} to="/change-password" size="sm">
        Change password
      </Anchor>
    </>
  );
}

/** Account section: the second factors of a local account. */
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
