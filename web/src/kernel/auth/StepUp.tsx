import { useState } from 'react';
import { Alert, Button, Group, PasswordInput, Stack, Text } from '@mantine/core';

import { needsReauthentication, useMe, useReauthenticate, type SecondFactorMethod } from './api.ts';
import { useFreshSignIn } from './freshSignIn.ts';
import { SecondFactorForm } from './SecondFactorForm.tsx';

/**
 * "Confirm it is you" (ADR-0103): a sensitive change — installing a plugin, changing a second factor — runs only
 * after a sign-in from the last five minutes, so a session that signed in earlier confirms first: with its password
 * and, when the account has one, its second factor; or by signing in again at its identity provider, which brings
 * the operator back to `returnTo`. Renders nothing once the session is fresh.
 */
export function StepUp({ returnTo }: Readonly<{ returnTo: string }>) {
  const me = useMe();
  const fresh = useFreshSignIn();
  const reauthenticate = useReauthenticate();
  const [password, setPassword] = useState('');
  const [empty, setEmpty] = useState(false);
  const [secondFactor, setSecondFactor] = useState<SecondFactorMethod[] | null>(null);
  const [restarted, setRestarted] = useState<string | null>(null);
  const reauth = me.data?.reauthentication;
  if (fresh || !reauth) return null;

  if (reauth.method === 'REDIRECT' && reauth.startPath) {
    const href = `${reauth.startPath}&returnTo=${encodeURIComponent(returnTo)}`;
    return (
      <Alert variant="light" title="Confirm it is you">
        <Stack gap="xs">
          <Text size="sm">
            Sign in again with your identity provider to continue. You come back here, and nothing you reviewed is lost.
          </Text>
          <Button component="a" href={href} w="fit-content">
            Sign in again
          </Button>
        </Stack>
      </Alert>
    );
  }

  if (secondFactor) {
    return (
      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Confirm it is you
        </Text>
        <SecondFactorForm
          methods={secondFactor}
          trustDeviceDays={0}
          onDone={() => setSecondFactor(null)}
          onRestart={({ message }) => {
            setSecondFactor(null);
            setRestarted(message);
          }}
          onBack={() => setSecondFactor(null)}
        />
      </Stack>
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
        setRestarted(null);
        reauthenticate.mutate(password, {
          onSuccess: (result) => {
            setPassword('');
            if (result.status === 'SECOND_FACTOR_REQUIRED') setSecondFactor(result.methods ?? []);
          },
        });
      }}
    >
      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Confirm it is you
        </Text>
        <Text size="sm" c="dimmed">
          Your last sign-in was more than five minutes ago. Re-enter your password; after five wrong attempts you are
          signed out.
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
            error={empty ? 'Enter your password.' : failed?.message}
            w={260}
          />
          <Button type="submit" loading={reauthenticate.isPending}>
            Confirm
          </Button>
        </Group>
        <div aria-live="polite">{restarted ? <Text size="sm">{restarted}</Text> : null}</div>
      </Stack>
    </form>
  );
}

/**
 * What an action shows when the server refused it with `reauthentication-required`: the step-up itself, then, once
 * confirmed, a line saying to try again, since the refused action did not run. Renders nothing for any other error.
 */
export function StepUpPrompt({ error, returnTo }: Readonly<{ error: unknown; returnTo: string }>) {
  const fresh = useFreshSignIn();
  if (!needsReauthentication(error)) return null;
  return (
    <>
      <div aria-live="polite">{fresh ? <Text size="sm">Confirmed. Try again.</Text> : null}</div>
      <StepUp returnTo={returnTo} />
    </>
  );
}
