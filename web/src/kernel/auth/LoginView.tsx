import { useState } from 'react';
import {
  Alert,
  Button,
  Center,
  Divider,
  Paper,
  PasswordInput,
  Select,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { ApiError, SESSION_ENDED_REASON } from '../api/request.ts';
import { useAuthProviders, useLogin, type AuthResult, type MeView, type SecondFactorMethod } from './api.ts';
import { SecondFactorForm, type Restart } from './SecondFactorForm.tsx';
import { bootState } from '../plugins/boot.ts';

/**
 * The login screen, built only from the installation's identity providers
 * (identity-and-sessions spec): a username and password form when a credential
 * provider exists — with a choice when there is more than one — and one sign-in
 * action per redirect provider. While the list loads the form is offered, so a
 * slow request never reads as "sign-in unavailable". An account with a second factor gets a second step
 * once its password is right (ADR-0142); nothing is signed in before it.
 */
export function LoginView() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [provider, setProvider] = useState<string | null>(null);
  const [secondStep, setSecondStep] = useState<{ methods: SecondFactorMethod[]; trustDeviceDays: number } | null>(null);
  const [restart, setRestart] = useState<Restart | null>(null);
  // Back from the second step, the username is right and the password is what needs typing.
  const [returned, setReturned] = useState(false);
  const login = useLogin();
  const providers = useAuthProviders();
  const navigate = useNavigate();

  const credential = (providers.data ?? []).filter((p) => p.kind === 'CREDENTIAL');
  const redirect = (providers.data ?? []).filter((p) => p.kind === 'REDIRECT');
  const listed = providers.data !== undefined;
  const showForm = !listed || credential.length > 0;
  const chosen = provider ?? credential[0]?.id ?? null;
  const sessionEnded = new URLSearchParams(window.location.search).get('reason') === SESSION_ENDED_REASON;

  function finish(me: MeView) {
    // Changing the password comes first: nothing else works until it is done.
    const to = me.mustChangePassword
      ? '/change-password'
      : me.secondFactorEnrolmentRequired
        ? '/enrol-second-factor'
        : '/';
    // A page that started signed out loaded no plugins (it could not read the manifest), so it
    // starts again, signed in; one that already has them just moves on.
    if (bootState().manifest === undefined) {
      window.location.replace(to);
    } else {
      void navigate({ to });
    }
  }

  function completed(result: AuthResult) {
    if (result.me) finish(result.me);
  }

  function onSubmit(e: React.SubmitEvent) {
    e.preventDefault();
    setRestart(null);
    login.mutate(
      { provider: chosen, username, password },
      {
        onSuccess: (result) => {
          if (result.status === 'SECOND_FACTOR_REQUIRED') {
            setSecondStep({ methods: result.methods ?? [], trustDeviceDays: result.trustDeviceDays });
          } else {
            completed(result);
          }
        },
      },
    );
  }

  function backToPassword(why: Restart | null) {
    setSecondStep(null);
    setReturned(true);
    setPassword('');
    setRestart(why);
    login.reset();
  }

  return (
    <Center mih="100vh" bg="var(--as-bg)">
      <Paper w={360} p="xl" radius="md" withBorder>
        <Stack gap="md">
          <Stack gap={2}>
            <Title order={3}>{branding.productName}</Title>
            <Text size="sm" c="dimmed">
              {secondStep ? 'Two-step verification' : 'Sign in to continue'}
            </Text>
          </Stack>

          {sessionEnded ? (
            <Alert color="gray" title="You were signed out" role="status">
              Your session ended after a period of inactivity, reached its maximum length, or was ended from another
              device or by an administrator. Sign in again to continue.
            </Alert>
          ) : null}

          {secondStep ? (
            <SecondFactorForm
              methods={secondStep.methods}
              trustDeviceDays={secondStep.trustDeviceDays}
              onDone={completed}
              onRestart={backToPassword}
              onBack={() => backToPassword(null)}
            />
          ) : null}

          {restart ? (
            <Alert color={restart.failed ? 'red' : 'gray'} role={restart.failed ? 'alert' : 'status'}>
              {restart.message}
            </Alert>
          ) : null}

          {showForm && !secondStep ? (
            <form onSubmit={onSubmit}>
              <Stack gap="sm">
                {credential.length > 1 ? (
                  <Select
                    label="Sign in with"
                    data={credential.map((p) => ({ value: p.id, label: p.label }))}
                    value={chosen}
                    onChange={setProvider}
                    allowDeselect={false}
                  />
                ) : null}
                <TextInput
                  label="Username"
                  autoFocus={!returned}
                  value={username}
                  onChange={(e) => setUsername(e.currentTarget.value)}
                  autoComplete="username"
                  required
                />
                <PasswordInput
                  label="Password"
                  value={password}
                  onChange={(e) => setPassword(e.currentTarget.value)}
                  autoComplete="current-password"
                  autoFocus={returned}
                  required
                />
                {login.isError ? <Alert color="red">{loginErrorMessage(login.error)}</Alert> : null}
                <Button type="submit" loading={login.isPending} fullWidth mt="xs">
                  Sign in
                </Button>
              </Stack>
            </form>
          ) : null}

          {redirect.length > 0 && !secondStep ? (
            <>
              {showForm ? <Divider label="or" labelPosition="center" /> : null}
              <Stack gap="xs">
                {redirect.map((p) => (
                  <Button key={p.id} component="a" href={p.startPath ?? undefined} variant="default" fullWidth>
                    Sign in with {p.label}
                  </Button>
                ))}
              </Stack>
            </>
          ) : null}

          {listed && !showForm && redirect.length === 0 && !secondStep ? (
            <Alert color="yellow">
              No sign-in method is configured on this installation. An administrator needs to enable local login or
              configure an identity provider.
            </Alert>
          ) : null}
        </Stack>
      </Paper>
    </Center>
  );
}

function loginErrorMessage(error: ApiError): string {
  if (error.status === 429) return 'Too many attempts. Try again in a moment.';
  return 'Invalid username or password.';
}
