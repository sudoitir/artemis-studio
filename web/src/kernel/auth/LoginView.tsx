import { useState } from 'react';
import { Alert, Button, Center, Divider, Paper, PasswordInput, Select, Stack, Text, TextInput, Title } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { ApiError } from '../api/request.ts';
import { useAuthProviders, useLogin } from './api.ts';
import { bootState } from '../plugins/boot.ts';

/**
 * The login screen, built only from the installation's identity providers
 * (identity-and-sessions spec): a username and password form when a credential
 * provider exists — with a choice when there is more than one — and one sign-in
 * action per redirect provider. While the list loads the form is offered, so a
 * slow request never reads as "sign-in unavailable".
 */
export function LoginView() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [provider, setProvider] = useState<string | null>(null);
  const login = useLogin();
  const providers = useAuthProviders();
  const navigate = useNavigate();

  const credential = (providers.data ?? []).filter((p) => p.kind === 'CREDENTIAL');
  const redirect = (providers.data ?? []).filter((p) => p.kind === 'REDIRECT');
  const listed = providers.data !== undefined;
  const showForm = !listed || credential.length > 0;
  const chosen = provider ?? credential[0]?.id ?? null;

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    login.mutate(
      { provider: chosen, username, password },
      {
        onSuccess: (me) => {
          const to = me.mustChangePassword ? '/change-password' : '/';
          // A page that started signed out loaded no plugins (it could not read the manifest), so it
          // starts again, signed in; one that already has them just moves on.
          if (bootState().manifest === undefined) {
            window.location.replace(to);
          } else {
            navigate({ to });
          }
        },
      },
    );
  }

  return (
    <Center mih="100vh" bg="var(--as-bg)">
      <Paper w={360} p="xl" radius="md" withBorder>
        <Stack gap="md">
          <Stack gap={2}>
            <Title order={3}>{branding.productName}</Title>
            <Text size="sm" c="dimmed">
              Sign in to continue
            </Text>
          </Stack>

          {showForm ? (
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
                  autoFocus
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
                  required
                />
                {login.isError ? <Alert color="red">{loginErrorMessage(login.error)}</Alert> : null}
                <Button type="submit" loading={login.isPending} fullWidth mt="xs">
                  Sign in
                </Button>
              </Stack>
            </form>
          ) : null}

          {redirect.length > 0 ? (
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

          {listed && !showForm && redirect.length === 0 ? (
            <Alert color="yellow">
              No sign-in method is configured on this installation. An administrator needs to enable local
              login or configure an identity provider.
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
