import { Anchor, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { TwoStepSection } from './TwoStepSection.tsx';

/** Account section: where a local account changes its password. */
export function PasswordSection() {
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
